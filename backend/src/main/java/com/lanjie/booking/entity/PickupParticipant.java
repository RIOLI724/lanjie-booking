package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pickup_participant")
public class PickupParticipant {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long gameId;

    private Long userId;

    private Long bookingId;

    private String status;

    private LocalDateTime createdAt;
}
