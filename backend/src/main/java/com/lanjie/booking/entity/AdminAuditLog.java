package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 管理端操作审计：谁在什么时候动了什么，前台可查。 */
@Data
@TableName("admin_audit_log")
public class AdminAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long actorId;

    private String actorName;

    private String action;

    private String targetType;

    private Long targetId;

    private String detail;

    private LocalDateTime createdAt;
}
