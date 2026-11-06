package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("coupon_template")
public class CouponTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** FIXED / PERCENT */
    private String type;

    private Integer valueCents;

    private Integer percent;

    private Integer maxDiscountCents;

    private Integer minAmountCents;

    /** ALL/COURT/COACH/GROUP/PICKUP */
    private String scope;

    private LocalDateTime validFrom;

    private LocalDateTime validTo;

    private Integer totalQuantity;

    private Integer issuedQuantity;

    private Integer perUserLimit;

    private String status;

    private LocalDateTime createdAt;
}
