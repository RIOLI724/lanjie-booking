package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 封禁：爽约触发，禁订 7 天；到点自动失效 */
@Data
@TableName("user_ban")
public class UserBan {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String reason;

    private LocalDateTime startAt;

    private LocalDateTime endAt;

    private LocalDateTime createdAt;
}
