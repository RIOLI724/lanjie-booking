package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_eval_case")
public class AiEvalCase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String category;

    private String input;

    private String expectedTools;

    private String expectedContains;

    private Boolean expectedHandoff;

    private Boolean enabled;
}
