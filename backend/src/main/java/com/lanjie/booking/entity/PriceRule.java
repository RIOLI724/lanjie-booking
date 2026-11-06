package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lanjie.booking.enums.DayType;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 价格规则：court_id 为 NULL 表示全馆默认；同一时段命中多条时取 priority 大者，
 * 再相同取 id 小者；全不命中回落到 court.basePriceCents。
 */
@Data
@TableName("price_rule")
public class PriceRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long courtId;

    private DayType dayType;

    private LocalTime startTime;

    private LocalTime endTime;

    private Integer priceCents;

    private Integer priority;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
