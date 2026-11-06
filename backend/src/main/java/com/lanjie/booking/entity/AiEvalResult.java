package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_eval_result")
public class AiEvalResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private Long caseId;

    private Boolean passed;

    private String actualTools;

    private String actualAnswer;

    private Integer latencyMs;

    private String error;
}
