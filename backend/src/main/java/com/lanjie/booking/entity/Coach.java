package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("coach")
public class Coach {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String displayName;

    private String avatarUrl;

    private String bio;

    private String specialties;

    private BigDecimal rating;

    private Integer ratingCount;

    private Integer basePriceCents;

    /** 平台抽成比例（0.200 = 20%） */
    private BigDecimal commissionRate;

    private String status;

    private Integer sortOrder;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
