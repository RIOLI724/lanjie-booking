package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lanjie.booking.enums.PackageStatus;
import lombok.Data;

import java.time.LocalDateTime;

/** 课包：5/10/20 节（95/90/85 折），购买日起 12 个月有效；付款成功才生效，取消 ≥24h 退回课时 */
@Data
@TableName("coach_package")
public class CoachPackage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long coachId;

    private Long serviceId;

    private Integer totalSessions;

    private Integer usedSessions;

    private Integer remainingSessions;

    private Integer priceCents;

    private Long payOrderId;

    private LocalDateTime expiresAt;

    private PackageStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
