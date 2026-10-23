package com.sky.mapper;

import com.sky.entity.ChatToolLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface ChatToolLogMapper {

    /**
     * 写入一条工具调用日志
     * @param log
     */
    @Insert("insert into chat_tool_log (user_id, user_text, tool_calls, rounds, prompt_tokens, " +
            "completion_tokens, total_tokens, duration_ms, success, create_time) " +
            "values (#{userId}, #{userText}, #{toolCalls}, #{rounds}, #{promptTokens}, " +
            "#{completionTokens}, #{totalTokens}, #{durationMs}, #{success}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(ChatToolLog log);
}
