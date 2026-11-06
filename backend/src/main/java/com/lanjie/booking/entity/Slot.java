package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lanjie.booking.enums.SlotStatus;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 可售时段（1 小时格子）。价格是生成时的快照，改价只影响未生成的格子。
 */
@Data
@TableName("slot")
public class Slot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long venueId;

    private Long courtId;

    private LocalDate slotDate;

    private LocalDateTime startAt;

    private LocalDateTime endAt;

    private Integer priceCents;

    private SlotStatus status;

    private String holdToken;

    private LocalDateTime holdExpiresAt;

    private String ownerType;

    private Long ownerId;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
