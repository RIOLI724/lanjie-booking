package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("points_account")
public class PointsAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Integer balance;

    private Integer totalEarned;

    private Integer totalSpent;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
