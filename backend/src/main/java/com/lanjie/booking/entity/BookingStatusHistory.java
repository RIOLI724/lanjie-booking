package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("booking_status_history")
public class BookingStatusHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bookingId;

    private String fromStatus;

    private String toStatus;

    private String actor;

    private String reason;

    private LocalDateTime createdAt;
}
