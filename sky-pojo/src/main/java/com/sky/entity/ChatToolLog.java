package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 智能客服工具调用日志（成本监控：token 消耗、轮次、耗时）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatToolLog implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    //用户id
    private Long userId;

    //用户消息原文
    private String userText;

    //工具调用明细（JSON 数组：[{tool,arguments,result,durationMs}]）
    private String toolCalls;

    //LLM 调用轮次
    private Integer rounds;

    //输入 token
    private Integer promptTokens;

    //输出 token
    private Integer completionTokens;

    //总 token
    private Integer totalTokens;

    //总耗时（毫秒）
    private Long durationMs;

    //是否成功接管（0 否 / 1 是）
    private Integer success;

    private LocalDateTime createTime;
}
