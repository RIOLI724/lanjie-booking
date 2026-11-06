package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lanjie.booking.enums.UserRole;
import com.lanjie.booking.enums.UserStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String phone;

    /** BCrypt 哈希；纯验证码登录的用户可以为空 */
    private String passwordHash;

    private String nickname;

    private String avatarUrl;

    private UserRole role;

    private UserStatus status;

    /** 会员等级，会员模块启用 */
    private Long memberLevelId;

    /** 累计充值（分，只算本金不算赠送） */
    private Long cumulativeRechargeCents;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
