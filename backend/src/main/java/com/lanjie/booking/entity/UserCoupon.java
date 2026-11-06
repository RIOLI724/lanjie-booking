package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_coupon")
public class UserCoupon {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long templateId;

    /** UNUSED / USED / EXPIRED */
    private String status;

    private LocalDateTime receivedAt;

    private LocalDateTime usedAt;

    private Long bookingId;

    private LocalDateTime expiresAt;
}
