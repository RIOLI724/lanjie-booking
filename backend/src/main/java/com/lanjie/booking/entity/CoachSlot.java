package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lanjie.booking.enums.SlotStatus;
import lombok.Data;

import java.time.LocalDateTime;

/** 教练档期：1 小时一格，结构与场地格一致 */
@Data
@TableName("coach_slot")
public class CoachSlot {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long coachId;

    private LocalDateTime startAt;

    private LocalDateTime endAt;

    private SlotStatus status;

    private String holdToken;

    private LocalDateTime holdExpiresAt;

    private String ownerType;

    private Long ownerId;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
