package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.AddressBook;
import com.sky.entity.ChatToolLog;
import com.sky.entity.Dish;
import com.sky.entity.Orders;
import com.sky.entity.ShoppingCart;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.ChatToolLogMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.OrderMapper;
import com.sky.service.ChatOrderService;
import com.sky.service.ChatToolService;
import com.sky.service.OrderService;
import com.sky.service.ShoppingCartService;
import com.sky.vo.OrderSubmitVO;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * LLM tool-calling 智能客服下单。
 *
 * 优先于关键词状态机调用：
 *  - LLM 理解用户意图 → 调用 Java 工具执行 → 循环直到最终回复
 *  - LLM 失败/超时 → 返回 null，调用方 fallback 到 ChatOrderService 关键词状态机
 */
@Service
@Slf4j
public class ChatToolServiceImpl implements ChatToolService {

    private static final String CHAT_ORDER_STREAM_URL = "http://127.0.0.1:8000/api/chat_order_stream";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_ROUNDS = 8;
    private static final long SESSION_TTL_SECONDS = 30 * 60;
    private static final double FUZZY_MIN_SCORE = 0.75;

    private static final String SESSION_KEY_PREFIX = "chat:session:";

    private static final HanyuPinyinOutputFormat PY_FORMAT = new HanyuPinyinOutputFormat();
    static {
        PY_FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        PY_FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
    }

    private final HttpClient httpClient;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private ShoppingCartService shoppingCartService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private ChatToolLogMapper chatToolLogMapper;

    public ChatToolServiceImpl() {
        // 必须显式指定 HTTP/1.1：JDK HttpClient 默认发起 h2c 升级（Upgrade: h2c），
        // 而 uvicorn/h11 不支持该升级请求，会丢弃请求体导致 FastAPI 返回 422。
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
    }

    @Override
    public boolean hasActiveSession(Long userId) {
        if (userId == null) return false;
        String key = SESSION_KEY_PREFIX + userId;
        Boolean exists = stringRedisTemplate.hasKey(key);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public void clearSession(Long userId) {
        if (userId == null) return;
        stringRedisTemplate.delete(SESSION_KEY_PREFIX + userId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getMessages(Long userId) {
        String key = SESSION_KEY_PREFIX + userId;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json != null && !json.isEmpty()) {
            try {
                return JSON.parseObject(json, new TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception e) {
                log.warn("Redis 会话反序列化失败 userId={}: {}", userId, e.getMessage());
            }
        }
        return new ArrayList<>();
    }

    private void saveMessages(Long userId, List<Map<String, Object>> messages) {
        if (userId == null || messages == null) return;
        String key = SESSION_KEY_PREFIX + userId;
        try {
            String json = JSON.toJSONString(messages);
            stringRedisTemplate.opsForValue().set(key, json, Duration.ofSeconds(SESSION_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("Redis 会话保存失败 userId={}: {}", userId, e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public ChatOrderService.Result handle(Long userId, String text, Consumer<String> onDelta) {
        if (userId == null || text == null || text.trim().isEmpty()) {
            return null;
        }

        List<Map<String, Object>> messages = getMessages(userId);
        // 加入用户消息
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", text.trim());
        messages.add(userMsg);

        // 成本监控：整轮 token / 轮次 / 耗时 / 工具调用明细
        long startMs = System.currentTimeMillis();
        int rounds = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        List<Map<String, Object>> toolRecords = new ArrayList<>();
        boolean success = false;

        try {
            // 第一轮调 Python（SSE 流式）
            Map<String, Object> resp = callPythonStream(messages, onDelta);
            rounds++;
            promptTokens += tokenOf(resp, "prompt_tokens");
            completionTokens += tokenOf(resp, "completion_tokens");
            totalTokens += tokenOf(resp, "total_tokens");
            if (resp == null || resp.containsKey("error")) {
                messages.remove(messages.size() - 1);
                return null;
            }

            // 工具调用循环
            for (int round = 0; round < MAX_ROUNDS; round++) {
                Boolean finish = (Boolean) resp.get("finish");
                if (Boolean.TRUE.equals(finish)) {
                    String content = (String) resp.get("content");
                    Map<String, Object> asstMsg = (Map<String, Object>) resp.get("assistant_message");
                    if (asstMsg != null) messages.add(asstMsg);
                    if (content == null || content.isEmpty()) {
                        content = "好的，已为您处理。";
                    }
                    success = true;
                    return ChatOrderService.Result.of(ChatOrderService.Reply.text(content));
                }

                // 有 tool_calls：先存 assistant 消息（含 tool_calls），再执行工具
                Map<String, Object> asstMsg = (Map<String, Object>) resp.get("assistant_message");
                if (asstMsg != null) messages.add(asstMsg);

                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) resp.get("tool_calls");
                if (toolCalls == null || toolCalls.isEmpty()) break;

                for (Map<String, Object> tc : toolCalls) {
                    String toolName = (String) tc.get("name");
                    Map<String, Object> args = (Map<String, Object>) tc.get("arguments");
                    String toolCallId = (String) tc.get("id");
                    long toolStart = System.currentTimeMillis();
                    String toolResult = executeTool(userId, toolName, args);
                    toolRecords.add(toolRecord(toolName, args, toolResult,
                            System.currentTimeMillis() - toolStart));

                    // 存 tool 结果消息（OpenAI 格式）
                    Map<String, Object> toolMsg = new HashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", toolCallId);
                    toolMsg.put("content", toolResult);
                    messages.add(toolMsg);
                }

                // 继续调 Python（SSE 流式）
                resp = callPythonStream(messages, onDelta);
                rounds++;
                promptTokens += tokenOf(resp, "prompt_tokens");
                completionTokens += tokenOf(resp, "completion_tokens");
                totalTokens += tokenOf(resp, "total_tokens");
                if (resp == null || resp.containsKey("error")) {
                    log.warn("LLM 续轮失败");
                    break;
                }
            }

            log.warn("LLM tool-calling 超过最大轮次或异常，fallback");
            return null;

        } catch (Exception e) {
            log.warn("LLM tool-calling 处理异常 userId={}: {}", userId, e.getMessage());
            messages.remove(messages.size() - 1);
            return null;
        } finally {
            // 统一持久化会话（含回滚后的空列表也存，覆盖旧数据）
            saveMessages(userId, messages);
            writeToolLog(userId, text.trim(), toolRecords, rounds, promptTokens,
                    completionTokens, totalTokens, System.currentTimeMillis() - startMs, success);
        }
    }

    /** 读取 Python 返回的 token 用量字段 */
    @SuppressWarnings("unchecked")
    private int tokenOf(Map<String, Object> resp, String key) {
        if (resp == null) return 0;
        Object usage = resp.get("usage");
        if (!(usage instanceof Map)) return 0;
        Object v = ((Map<String, Object>) usage).get(key);
        return (v instanceof Number) ? ((Number) v).intValue() : 0;
    }

    /** 组装单次工具调用明细 */
    private Map<String, Object> toolRecord(String name, Map<String, Object> args,
                                           String result, long durationMs) {
        Map<String, Object> rec = new HashMap<>();
        rec.put("tool", name);
        rec.put("arguments", args);
        rec.put("result", result);
        rec.put("durationMs", durationMs);
        return rec;
    }

    /** 落库一条工具调用日志；日志失败不能影响正常对话 */
    private void writeToolLog(Long userId, String userText, List<Map<String, Object>> toolRecords,
                              int rounds, int promptTokens, int completionTokens, int totalTokens,
                              long durationMs, boolean success) {
        try {
            ChatToolLog logRow = ChatToolLog.builder()
                    .userId(userId)
                    .userText(truncate(userText, 500))
                    .toolCalls(JSON.toJSONString(toolRecords))
                    .rounds(rounds)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .totalTokens(totalTokens)
                    .durationMs(durationMs)
                    .success(success ? 1 : 0)
                    .createTime(LocalDateTime.now())
                    .build();
            chatToolLogMapper.insert(logRow);
        } catch (Exception e) {
            log.warn("工具调用日志写入失败 userId={}: {}", userId, e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 调用 Python SSE 接口。逐行读取 data: 事件：
     *  - type=delta：正文增量，交给 onDelta 实时推送
     *  - type=result：本轮最终结果（含 tool_calls / assistant_message / usage），作为返回值
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callPythonStream(List<Map<String, Object>> messages, Consumer<String> onDelta) {
        Map<String, Object> req = new HashMap<>();
        req.put("messages", messages);
        String body = JSON.toJSONString(req);

        try {
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(CHAT_ORDER_STREAM_URL))
                    .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                String err = "";
                try (InputStream is = resp.body()) {
                    err = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception ignore) {
                    // 读取错误响应体失败时忽略，仅记录状态码
                }
                log.warn("Python 流式接口返回 {}: {}", resp.statusCode(), truncate(err, 400));
                return null;
            }

            Map<String, Object> result = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) continue;
                    Map<String, Object> event;
                    try {
                        event = JSON.parseObject(payload, new TypeReference<Map<String, Object>>() {});
                    } catch (Exception ignore) {
                        continue;
                    }
                    if (event == null) continue;
                    String type = (String) event.get("type");
                    if ("delta".equals(type)) {
                        String delta = (String) event.get("text");
                        if (delta != null && !delta.isEmpty() && onDelta != null) {
                            onDelta.accept(delta);
                        }
                    } else if ("result".equals(type)) {
                        result = event;
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("调用 Python 流式接口失败: {}", e.getMessage());
            return null;
        }
    }

    // ---- 工具执行 ----

    private String executeTool(Long userId, String toolName, Map<String, Object> args) {
        if (args == null) args = new HashMap<>();
        BaseContext.setCurrentId(userId);
        try {
            switch (toolName) {
                case "get_menu":          return toolGetMenu();
                case "add_to_cart":       return toolAddToCart(args);
                case "remove_from_cart":  return toolRemoveFromCart(args);
                case "update_quantity":   return toolUpdateQuantity(args);
                case "get_addresses":     return toolGetAddresses(userId);
                case "submit_order":      return toolSubmitOrder(args);
                case "clear_cart":        return toolClearCart();
                case "get_cart":          return toolGetCart();
                case "get_order_status":  return toolGetOrderStatus(args);
                default: return jsonError("未知工具: " + toolName);
            }
        } catch (Exception e) {
            log.error("工具执行异常 {}: {}", toolName, e.getMessage());
            return jsonError("工具执行失败: " + e.getMessage());
        }
    }

    private String jsonError(String msg) {
        return "{\"error\":\"" + msg.replace("\"", "\\\"") + "\"}";
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String safe(String s) { return s == null ? "" : s; }

    private String toolGetMenu() {
        List<Dish> list = dishMapper.list(Dish.builder().status(StatusConstant.ENABLE).build());
        if (list == null || list.isEmpty()) return "{\"dishes\":[]}";
        StringBuilder sb = new StringBuilder("{\"dishes\":[");
        for (int i = 0; i < list.size(); i++) {
            Dish d = list.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(d.getId())
              .append(",\"name\":\"").append(escape(d.getName())).append("\"")
              .append(",\"price\":").append(d.getPrice())
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String toolAddToCart(Map<String, Object> args) {
        String dishName = (String) args.get("dish_name");
        if (dishName == null || dishName.trim().isEmpty()) return jsonError("缺少菜名");
        int qty = 1;
        Object qObj = args.get("quantity");
        if (qObj instanceof Number) qty = ((Number) qObj).intValue();
        if (qty < 1) qty = 1;
        if (qty > 99) qty = 99;

        List<Dish> menu = dishMapper.list(Dish.builder().status(StatusConstant.ENABLE).build());
        if (menu == null) return jsonError("菜单不可用");
        Dish matched = null;
        String target = dishName.trim();

        // 1. 精确匹配（唯一）
        for (Dish d : menu) {
            if (d.getName() != null && d.getName().equals(target)) { matched = d; break; }
        }
        if (matched != null) return doAdd(matched, qty);

        // 2. 包含匹配（唯一）
        for (Dish d : menu) {
            if (d.getName() != null && (d.getName().contains(target) || target.contains(d.getName()))) {
                matched = d; break;
            }
        }
        if (matched != null) return doAdd(matched, qty);

        // 3. 拼音匹配：收集所有候选，多个则返回列表让用户确认
        String pTarget = pinyinOf(target);
        List<Dish> pinyinCandidates = new ArrayList<>();
        for (Dish d : menu) {
            String name = d.getName();
            if (name == null) continue;
            String pName = pinyinOf(name);
            if (pTarget.equals(pName) || pTarget.contains(pName) || pName.contains(pTarget)) {
                pinyinCandidates.add(d);
            }
        }
        if (pinyinCandidates.size() == 1) {
            return doAdd(pinyinCandidates.get(0), qty);
        }
        if (pinyinCandidates.size() > 1) {
            return buildCandidateJson(pinyinCandidates);
        }

        // 4. 编辑距离兜底：收集所有≥阈值的候选
        List<Dish> fuzzyCandidates = new ArrayList<>();
        double best = 0;
        for (Dish d : menu) {
            String name = d.getName();
            if (name == null) continue;
            double sim = similarity(target, name);
            if (sim >= FUZZY_MIN_SCORE) {
                fuzzyCandidates.add(d);
                best = Math.max(best, sim);
            }
        }
        if (fuzzyCandidates.isEmpty()) return jsonError("未找到菜品: " + dishName);
        if (fuzzyCandidates.size() == 1) return doAdd(fuzzyCandidates.get(0), qty);
        return buildCandidateJson(fuzzyCandidates);
    }

    private String doAdd(Dish matched, int qty) {
        ShoppingCartDTO dto = new ShoppingCartDTO();
        dto.setDishId(matched.getId());
        for (int i = 0; i < qty; i++) {
            shoppingCartService.addShoppingCart(dto);
        }
        return "{\"ok\":true,\"dish\":\"" + escape(matched.getName()) + "\",\"quantity\":" + qty + "}";
    }

    private String buildCandidateJson(List<Dish> candidates) {
        StringBuilder sb = new StringBuilder("{\"ok\":false,\"candidates\":[");
        for (int i = 0; i < candidates.size(); i++) {
            Dish d = candidates.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(d.getId())
              .append(",\"name\":\"").append(escape(d.getName())).append("\"")
              .append(",\"price\":").append(d.getPrice())
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 转无分隔拼音串（非汉字保留原文），用于同音错别字匹配：宫爆/宫保 → gongbao */
    private String pinyinOf(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            String[] py = null;
            try {
                py = PinyinHelper.toHanyuPinyinStringArray(c, PY_FORMAT);
            } catch (BadHanyuPinyinOutputFormatCombination e) {
                // 忽略格式异常，按非汉字处理
            }
            if (py != null && py.length > 0) {
                sb.append(py[0]);
            } else if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** 相似度 = 1 - 编辑距离/较长串长度 */
    private double similarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        int dist = levenshtein(a, b);
        return 1.0 - (double) dist / Math.max(a.length(), b.length());
    }

    private int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }

    private String toolGetAddresses(Long userId) {
        AddressBook query = new AddressBook();
        query.setUserId(userId);
        List<AddressBook> list = addressBookMapper.list(query);
        if (list == null || list.isEmpty()) return "{\"addresses\":[]}";
        StringBuilder sb = new StringBuilder("{\"addresses\":[");
        for (int i = 0; i < list.size(); i++) {
            AddressBook a = list.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(a.getId())
              .append(",\"consignee\":\"").append(escape(a.getConsignee())).append("\"")
              .append(",\"phone\":\"").append(escape(a.getPhone())).append("\"")
              .append(",\"detail\":\"").append(escape(safe(a.getProvinceName()) + safe(a.getCityName()) + safe(a.getDistrictName()) + safe(a.getDetail()))).append("\"")
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String toolSubmitOrder(Map<String, Object> args) {
        Object addrIdObj = args.get("address_id");
        if (!(addrIdObj instanceof Number)) return jsonError("缺少地址ID");
        Long addressId = ((Number) addrIdObj).longValue();
        if (addressId <= 0) return jsonError("地址ID不合法");

        // 权限校验：地址必须属于当前用户（防 LLM 幻觉地址ID / 越权下单）
        AddressBook addrQuery = new AddressBook();
        addrQuery.setUserId(BaseContext.getCurrentId());
        List<AddressBook> ownAddresses = addressBookMapper.list(addrQuery);
        boolean owned = false;
        if (ownAddresses != null) {
            for (AddressBook a : ownAddresses) {
                if (addressId.equals(a.getId())) { owned = true; break; }
            }
        }
        if (!owned) return jsonError("地址不存在或不属于当前用户");

        List<ShoppingCart> cart = shoppingCartService.showShoppingCart();
        if (cart == null || cart.isEmpty()) return jsonError("购物车为空");

        BigDecimal total = BigDecimal.ZERO;
        for (ShoppingCart c : cart) {
            if (c.getAmount() != null) total = total.add(c.getAmount().multiply(BigDecimal.valueOf(c.getNumber())));
        }
        BigDecimal amount = total.add(new BigDecimal("7.00")).setScale(2, RoundingMode.HALF_UP);

        OrdersSubmitDTO submit = new OrdersSubmitDTO();
        submit.setAddressBookId(addressId);
        submit.setPayMethod(1);
        submit.setRemark("");
        submit.setEstimatedDeliveryTime(null);
        submit.setDeliveryStatus(1);
        submit.setTablewareNumber(1);
        submit.setTablewareStatus(1);
        submit.setPackAmount(1);
        submit.setAmount(amount);

        OrderSubmitVO vo = orderService.submitOrder(submit);
        log.info("LLM tool-calling 下单成功 orderId={}", vo.getId());
        return "{\"ok\":true,\"order_id\":" + vo.getId()
             + ",\"order_number\":\"" + escape(vo.getOrderNumber()) + "\""
             + ",\"amount\":" + vo.getOrderAmount() + "}";
    }

    private String toolClearCart() {
        shoppingCartService.cleanShoppingCart();
        return "{\"ok\":true}";
    }

    private String toolGetCart() {
        List<ShoppingCart> cart = shoppingCartService.showShoppingCart();
        if (cart == null || cart.isEmpty()) return "{\"items\":[]}";
        StringBuilder sb = new StringBuilder("{\"items\":[");
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < cart.size(); i++) {
            ShoppingCart c = cart.get(i);
            if (i >  0) sb.append(",");
            sb.append("{\"name\":\"").append(escape(c.getName())).append("\"")
              .append(",\"quantity\":").append(c.getNumber())
              .append(",\"amount\":").append(c.getAmount())
              .append("}");
            if (c.getAmount() != null) total = total.add(c.getAmount().multiply(BigDecimal.valueOf(c.getNumber())));
        }
        sb.append("],\"total\":").append(total).append("}");
        return sb.toString();
    }

    /** 从购物车整项移除某菜品（不是减数量，是整项删除） */
    private String toolRemoveFromCart(Map<String, Object> args) {
        String dishName = (String) args.get("dish_name");
        if (dishName == null || dishName.trim().isEmpty()) return jsonError("缺少菜名");

        List<ShoppingCart> cart = shoppingCartService.showShoppingCart();
        if (cart == null || cart.isEmpty()) return jsonError("购物车是空的");

        List<ShoppingCart> candidates = matchCartItems(cart, dishName.trim());
        if (candidates.isEmpty()) return jsonError("购物车里没有找到：" + dishName);
        if (candidates.size() > 1) return buildCartCandidateJson(candidates);

        ShoppingCart item = candidates.get(0);
        shoppingCartService.removeItemFromCart(buildCartDto(item));
        return "{\"ok\":true,\"dish\":\"" + escape(item.getName()) + "\",\"removed\":true}";
    }

    /** 修改购物车中某菜品的份数（绝对值；0 表示移除） */
    private String toolUpdateQuantity(Map<String, Object> args) {
        String dishName = (String) args.get("dish_name");
        if (dishName == null || dishName.trim().isEmpty()) return jsonError("缺少菜名");
        Object qObj = args.get("quantity");
        if (!(qObj instanceof Number)) return jsonError("缺少份数");
        int qty = ((Number) qObj).intValue();
        if (qty < 0) qty = 0;
        if (qty > 99) qty = 99;

        List<ShoppingCart> cart = shoppingCartService.showShoppingCart();
        if (cart == null || cart.isEmpty()) return jsonError("购物车是空的");

        List<ShoppingCart> candidates = matchCartItems(cart, dishName.trim());
        if (candidates.isEmpty()) return jsonError("购物车里没有找到：" + dishName);
        if (candidates.size() > 1) return buildCartCandidateJson(candidates);

        ShoppingCart item = candidates.get(0);
        shoppingCartService.updateItemQuantity(buildCartDto(item), qty);
        if (qty == 0) {
            return "{\"ok\":true,\"dish\":\"" + escape(item.getName()) + "\",\"removed\":true}";
        }
        return "{\"ok\":true,\"dish\":\"" + escape(item.getName()) + "\",\"quantity\":" + qty + "}";
    }

    private ShoppingCartDTO buildCartDto(ShoppingCart item) {
        ShoppingCartDTO dto = new ShoppingCartDTO();
        dto.setDishId(item.getDishId());
        dto.setSetmealId(item.getSetmealId());
        dto.setDishFlavor(item.getDishFlavor());
        return dto;
    }

    /** 在购物车中模糊匹配菜品：精确 → 包含 → 拼音 → 编辑距离，返回命中列表 */
    private List<ShoppingCart> matchCartItems(List<ShoppingCart> cart, String target) {
        List<ShoppingCart> exact = new ArrayList<>();
        for (ShoppingCart c : cart) {
            if (c.getName() != null && c.getName().equals(target)) exact.add(c);
        }
        if (!exact.isEmpty()) return exact;

        List<ShoppingCart> contains = new ArrayList<>();
        for (ShoppingCart c : cart) {
            String n = c.getName();
            if (n != null && (n.contains(target) || target.contains(n))) contains.add(c);
        }
        if (!contains.isEmpty()) return contains;

        String pTarget = pinyinOf(target);
        List<ShoppingCart> pinyinHits = new ArrayList<>();
        for (ShoppingCart c : cart) {
            String n = c.getName();
            if (n == null) continue;
            String pName = pinyinOf(n);
            if (pTarget.equals(pName) || pTarget.contains(pName) || pName.contains(pTarget)) pinyinHits.add(c);
        }
        if (!pinyinHits.isEmpty()) return pinyinHits;

        List<ShoppingCart> fuzzy = new ArrayList<>();
        for (ShoppingCart c : cart) {
            String n = c.getName();
            if (n == null) continue;
            if (similarity(target, n) >= FUZZY_MIN_SCORE) fuzzy.add(c);
        }
        return fuzzy;
    }

    private String buildCartCandidateJson(List<ShoppingCart> candidates) {
        StringBuilder sb = new StringBuilder("{\"ok\":false,\"candidates\":[");
        for (int i = 0; i < candidates.size(); i++) {
            ShoppingCart c = candidates.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"name\":\"").append(escape(c.getName())).append("\"")
              .append(",\"quantity\":").append(c.getNumber())
              .append(",\"amount\":").append(c.getAmount())
              .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 查询订单状态：传 order_number 按订单号查，否则查用户最近一笔订单 */
    private String toolGetOrderStatus(Map<String, Object> args) {
        Long userId = BaseContext.getCurrentId();
        Object numObj = args.get("order_number");
        String number = numObj == null ? null : numObj.toString().trim();

        Orders order;
        if (number != null && !number.isEmpty()) {
            // 参数校验：订单号必须是纯数字，防 LLM 幻觉参数直查数据库
            if (!number.matches("\\d{1,32}")) {
                return jsonError("订单号格式不正确");
            }
            order = orderMapper.getByNumberAndUserId(number, userId);
        } else {
            order = orderMapper.getLatestByUserId(userId);
        }
        if (order == null) return "{\"ok\":false,\"message\":\"没有找到订单\"}";
        return "{\"ok\":true,\"order_id\":" + order.getId()
             + ",\"order_number\":\"" + escape(order.getNumber()) + "\""
             + ",\"status\":" + order.getStatus()
             + ",\"status_text\":\"" + statusText(order.getStatus()) + "\""
             + ",\"amount\":" + order.getAmount() + "}";
    }

    private String statusText(Integer status) {
        if (status == null) return "未知";
        switch (status) {
            case 1: return "待付款";
            case 2: return "待接单";
            case 3: return "已接单";
            case 4: return "派送中";
            case 5: return "已完成";
            case 6: return "已取消";
            default: return "未知";
        }
    }
}
