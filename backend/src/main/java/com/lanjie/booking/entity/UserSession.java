package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录会话：refresh token 只存哈希；每次刷新轮换（旧 token 撤销），登出即撤销。
 */
@Data
@TableName("user_session")
public class UserSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String refreshTokenHash;

    private String device;

    private LocalDateTime expiresAt;

    private LocalDateTime revokedAt;

    private LocalDateTime createdAt;
}
