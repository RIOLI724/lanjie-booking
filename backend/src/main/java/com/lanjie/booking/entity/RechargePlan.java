package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("recharge_plan")
public class RechargePlan {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer amountCents;

    private Integer bonusCents;

    private String title;

    private String status;

    private Integer sort;
}
