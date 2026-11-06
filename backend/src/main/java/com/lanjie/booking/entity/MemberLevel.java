package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("member_level")
public class MemberLevel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String code;

    private String name;

    /** 累计充值门槛（分，只算本金） */
    private Long thresholdCents;

    /** 折扣百分位：95 = 95 折 */
    private Integer discountPct;

    private BigDecimal pointsRate;

    private Integer sort;
}
