package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_tool_call")
public class AiToolCall {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private Long messageId;

    private String toolName;

    private String argsJson;

    private String resultJson;

    private String status;

    private String error;

    private Integer latencyMs;

    private LocalDateTime createdAt;
}
