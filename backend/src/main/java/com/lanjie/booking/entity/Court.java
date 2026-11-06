package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lanjie.booking.enums.CourtStatus;
import com.lanjie.booking.enums.CourtType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("court")
public class Court {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long venueId;

    private String name;

    private CourtType type;

    /** 半场所属整场；整场为 NULL */
    private Long parentId;

    /** 基础价（分/小时），价格规则都不命中时兜底 */
    private Integer basePriceCents;

    private CourtStatus status;

    private Integer sortOrder;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
