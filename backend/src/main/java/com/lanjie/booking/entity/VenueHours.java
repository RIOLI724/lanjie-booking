package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalTime;

/** 每周营业时间：1=周一 … 7=周日 */
@Data
@TableName("venue_hours")
public class VenueHours {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long venueId;

    private Integer weekday;

    private LocalTime openTime;

    private LocalTime closeTime;
}
