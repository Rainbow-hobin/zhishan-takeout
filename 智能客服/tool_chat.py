"""LLM tool-calling 模块：定义点餐工具 + system prompt + LLM 调用循环。"""
import json
from openai import OpenAI
from app import config

# ---- 工具定义（OpenAI function calling 格式） ----
TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_menu",
            "description": "获取当前在售菜品列表。返回菜名、价格。用户问有什么菜、想看菜单时调用。",
            "parameters": {"type": "object", "properties": {}}
        }
    },
    {
        "type": "function",
        "function": {
            "name": "add_to_cart",
            "description": "将菜品加入购物车。dish_name 用用户原话即可（如“宫爆鸡丁”），内部会模糊匹配到真实菜名。quantity 默认1。",
            "parameters": {
                "type": "object",
                "properties": {
                    "dish_name": {"type": "string", "description": "菜品名称（用户口语表达）"},
                    "quantity": {"type": "integer", "description": "份数", "default": 1}
                },
                "required": ["dish_name"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_addresses",
            "description": "获取用户收货地址列表。下单前需要让用户选择地址时调用。",
            "parameters": {"type": "object", "properties": {}}
        }
    },
    {
        "type": "function",
        "function": {
            "name": "submit_order",
            "description": "提交订单。调用前必须已和用户确认了菜品和地址。address_id 是地址编号。",
            "parameters": {
                "type": "object",
                "properties": {
                    "address_id": {"type": "integer", "description": "收货地址ID"}
                },
                "required": ["address_id"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "clear_cart",
            "description": "清空购物车。",
            "parameters": {"type": "object", "properties": {}}
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_cart",
            "description": "查看当前购物车内容。",
            "parameters": {"type": "object", "properties": {}}
        }
    },
    {
        "type": "function",
        "function": {
            "name": "remove_from_cart",
            "description": "从购物车中整项删除某道菜（不是减少份数，是彻底移除）。用户说“不要了/删掉/去掉xx”时调用。dish_name 用用户原话。",
            "parameters": {
                "type": "object",
                "properties": {
                    "dish_name": {"type": "string", "description": "要删除的菜品名称（用户口语表达）"}
                },
                "required": ["dish_name"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "update_quantity",
            "description": "修改购物车中某道菜的份数。quantity 是目标份数（绝对值），不是增减量：从2份改成3份传 3，改成1份传 1，传 0 表示移除。",
            "parameters": {
                "type": "object",
                "properties": {
                    "dish_name": {"type": "string", "description": "菜品名称（用户口语表达）"},
                    "quantity": {"type": "integer", "description": "目标份数（绝对值，0 表示移除）"}
                },
                "required": ["dish_name", "quantity"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "get_order_status",
            "description": "查询订单状态（如待付款/待接单/已接单/派送中/已完成/已取消）。用户问“我的订单到哪了/做好了没/发货了吗/订单状态”时调用。不传 order_number 则查最近一笔订单。",
            "parameters": {
                "type": "object",
                "properties": {
                    "order_number": {"type": "string", "description": "订单号，可选；不传则查最近一笔订单"}
                }
            }
        }
    }
]

SYSTEM_PROMPT = """你是外卖点餐助手。以下情况必须调用对应工具，严禁只用文字回复：

- 用户提到菜名（如"帮我点2份宫保鸡丁""来一份宫保鸡丁"）→ 调 add_to_cart，dish_name 用用户原话
- 用户问有什么菜/看菜单 → 调 get_menu
- 用户说不要某道菜/删掉/去掉 → 调 remove_from_cart（整项删除，不是减份数）
- 用户说要把某道菜改成N份/换成N份 → 调 update_quantity，quantity 填目标份数（绝对值）
- 用户问订单到哪了/订单状态/发货了吗/做好了没 → 调 get_order_status
- 用户说好了/下单/结算 → 先调 get_cart 展示购物车，再调 get_addresses 让用户选地址
- 用户确认下单 → 调 submit_order
- 任何工具返回 ok:false + candidates 时，必须把候选列表展示给用户并让用户选编号，不能擅自选

信息不足时必须反问澄清，严禁猜测执行：
- 用户说"我要退/改一下/换一个"但没说具体是哪道菜或哪笔订单 → 先用文字反问清楚，不要调工具
- 用户要删菜但购物车里有多个相似菜品 → 展示候选让用户选编号

只有以下情况用文字回复，不调工具：
- 用户问营业时间、退款政策等非点餐问题
- 用户要求转人工
- 信息不足需要反问澄清
- 工具返回 candidates 候选列表时，用文字让用户选编号

示例：
用户：帮我点2份宫保鸡丁 → 你必须调 add_to_cart(dish_name="宫保鸡丁", quantity=2)
工具返回 ok:true → 回复"已加入购物车：宫保鸡丁×2"
工具返回 candidates:[宫保鸡丁,宫保虾球] → 回复"找到两个菜，请选编号：1.宫保鸡丁 2.宫保虾球"
用户：有什么菜 → 你必须调 get_menu()
用户：宫保鸡丁不要了 → 你必须调 remove_from_cart(dish_name="宫保鸡丁")
用户：宫保鸡丁帮我改成3份 → 你必须调 update_quantity(dish_name="宫保鸡丁", quantity=3)
用户：我的订单到哪了 → 你必须调 get_order_status()
用户：我要退 → 你必须先用文字反问"请问您要退哪道菜或哪笔订单？"，不要调工具
用户：好了下单 → 你必须调 get_cart() 展示购物车，然后调 get_addresses()

回复简短友好。"""


def call_llm(messages):
    """调用 DeepSeek，返回 chat completion 响应。"""
    client = OpenAI(api_key=config.DEEPSEEK_API_KEY, base_url=config.LLM_BASE_URL)
    resp = client.chat.completions.create(
        model=config.LLM_MODEL,
        messages=messages,
        tools=TOOLS,
        tool_choice="auto",
        temperature=0.3,
    )
    return resp


def call_llm_stream(messages):
    """流式调用 DeepSeek，返回 chunk 迭代器（附带 token 用量）。"""
    client = OpenAI(api_key=config.DEEPSEEK_API_KEY, base_url=config.LLM_BASE_URL)
    return client.chat.completions.create(
        model=config.LLM_MODEL,
        messages=messages,
        tools=TOOLS,
        tool_choice="auto",
        temperature=0.3,
        stream=True,
        stream_options={"include_usage": True},
    )
