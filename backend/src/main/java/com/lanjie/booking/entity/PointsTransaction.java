package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("points_transaction")
public class PointsTransaction {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** EARN / SPEND / REFUND / ADJUST */
    private String type;

    private Integer points;

    private Integer balanceAfter;

    private String bizType;

    private Long bizId;

    private LocalDateTime createdAt;
}
