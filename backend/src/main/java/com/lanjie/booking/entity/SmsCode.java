package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lanjie.booking.enums.SmsScene;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 短信验证码：只存哈希；5 分钟有效；失败 5 次由 Redis 锁定 15 分钟。
 */
@Data
@TableName("sms_code")
public class SmsCode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String phone;

    private SmsScene scene;

    private String codeHash;

    private int attempts;

    private LocalDateTime expiresAt;

    private LocalDateTime usedAt;

    private LocalDateTime createdAt;
}
