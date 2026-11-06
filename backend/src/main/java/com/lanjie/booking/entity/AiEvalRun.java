package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_eval_run")
public class AiEvalRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String model;

    private String promptVersion;

    private Integer total;

    private Integer passed;

    private Integer failed;

    private Integer costCents;

    private String reportJson;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
