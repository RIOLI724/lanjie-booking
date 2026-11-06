package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lanjie.booking.enums.VenueStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@TableName("venue")
public class Venue {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String address;

    private String phone;

    /** 默认营业时间（venue_hours 未配置的星期用这个兜底） */
    private LocalTime openTime;

    private LocalTime closeTime;

    private String timezone;

    private VenueStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
