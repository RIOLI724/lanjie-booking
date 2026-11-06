package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pickup_game")
public class PickupGame {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long initiatorId;

    /** 发起人的场地订单（付全场） */
    private Long bookingId;

    private Long slotId;

    private String format;

    private Integer totalPlayers;

    private Integer joinedPlayers;

    private Integer pricePerPersonCents;

    private String note;

    private String status;

    private Boolean settled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
