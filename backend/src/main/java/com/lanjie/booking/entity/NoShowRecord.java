package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 爽约记录：付费未到场；7 天内 3 次触发禁订 7 天 */
@Data
@TableName("no_show_record")
public class NoShowRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long bookingId;

    private LocalDateTime createdAt;
}
