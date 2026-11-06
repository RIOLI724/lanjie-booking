package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.entity.AdminAuditLog;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.mapper.AdminAuditLogMapper;
import com.lanjie.booking.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 记录前台敏感操作；查询按日期/动作筛选，按时间倒序。 */
@Slf4j
@Service
public class AdminAuditService {

    private final AdminAuditLogMapper auditMapper;
    private final UserMapper userMapper;

    public AdminAuditService(AdminAuditLogMapper auditMapper, UserMapper userMapper) {
        this.auditMapper = auditMapper;
        this.userMapper = userMapper;
    }

    public void record(Long actorId, String action, String targetType, Long targetId, String detail) {
        AdminAuditLog entry = new AdminAuditLog();
        entry.setActorId(actorId);
        User actor = actorId != null ? userMapper.selectById(actorId) : null;
        entry.setActorName(actor != null ? actor.getNickname() : "系统");
        entry.setAction(action);
        entry.setTargetType(targetType == null ? "" : targetType);
        entry.setTargetId(targetId);
        String text = detail == null ? "" : detail;
        entry.setDetail(text.length() > 500 ? text.substring(0, 500) : text);
        entry.setCreatedAt(LocalDateTime.now());
        auditMapper.insert(entry);
        log.info("admin audit: actor={} action={} target={}:{}", actorId, action, targetType, targetId);
    }

    public List<AdminAuditLog> query(LocalDate date, String action, int limit) {
        LocalDate day = date == null ? LocalDate.now() : date;
        int size = Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<AdminAuditLog> query = new LambdaQueryWrapper<AdminAuditLog>()
                .ge(AdminAuditLog::getCreatedAt, day.atStartOfDay())
                .lt(AdminAuditLog::getCreatedAt, day.plusDays(1).atStartOfDay())
                .orderByDesc(AdminAuditLog::getId)
                .last("LIMIT " + size);
        if (action != null && !action.isBlank()) {
            query.eq(AdminAuditLog::getAction, action);
        }
        return auditMapper.selectList(query);
    }
}
