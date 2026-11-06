package com.lanjie.booking.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.Result;
import com.lanjie.booking.entity.AiHandoff;
import com.lanjie.booking.entity.AiEvalRun;
import com.lanjie.booking.entity.AiMessage;
import com.lanjie.booking.entity.AiSession;
import com.lanjie.booking.entity.AiToolCall;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.mapper.AiHandoffMapper;
import com.lanjie.booking.mapper.AiMessageMapper;
import com.lanjie.booking.mapper.AiSessionMapper;
import com.lanjie.booking.mapper.AiToolCallMapper;
import com.lanjie.booking.mapper.UserMapper;
import com.lanjie.booking.service.AiEvalService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** AI 观测台：会话/工具调用/转人工工单/评测（PC 管理端数据源） */
@RestController
@RequestMapping("/api/v1/admin/ai")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAiController {

    private final AiSessionMapper sessionMapper;
    private final AiToolCallMapper toolCallMapper;
    private final AiHandoffMapper handoffMapper;
    private final AiMessageMapper messageMapper;
    private final UserMapper userMapper;
    private final AiEvalService evalService;

    public AdminAiController(AiSessionMapper sessionMapper, AiToolCallMapper toolCallMapper,
                             AiHandoffMapper handoffMapper, AiMessageMapper messageMapper,
                             UserMapper userMapper, AiEvalService evalService) {
        this.sessionMapper = sessionMapper;
        this.toolCallMapper = toolCallMapper;
        this.handoffMapper = handoffMapper;
        this.messageMapper = messageMapper;
        this.userMapper = userMapper;
        this.evalService = evalService;
    }

    @GetMapping("/sessions")
    public Result<List<Map<String, Object>>> sessions() {
        List<AiSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<AiSession>()
                .orderByDesc(AiSession::getId).last("LIMIT 30"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiSession session : sessions) {
            List<AiToolCall> calls = toolCallMapper.selectList(new LambdaQueryWrapper<AiToolCall>()
                    .eq(AiToolCall::getSessionId, session.getId()));
            long latency = calls.stream().mapToLong(c -> c.getLatencyMs() == null ? 0 : c.getLatencyMs()).sum();
            result.add(Map.of("sessionId", session.getId(), "userId", session.getUserId(),
                    "status", session.getStatus(), "model", session.getModel(),
                    "tools", calls.size(), "latencyMs", latency,
                    "startedAt", session.getStartedAt().toString()));
        }
        return Result.ok(result);
    }

    @GetMapping("/handoffs")
    public Result<List<Map<String, Object>>> handoffs() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AiHandoff handoff : handoffMapper.selectList(new LambdaQueryWrapper<AiHandoff>()
                .orderByAsc(AiHandoff::getStatus).orderByDesc(AiHandoff::getId).last("LIMIT 50"))) {
            User user = userMapper.selectById(handoff.getUserId());
            if (user != null && user.getRole() == com.lanjie.booking.enums.UserRole.ADMIN) {
                continue; // 评测/自测数据不展示
            }
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", handoff.getId());
            row.put("sessionId", handoff.getSessionId());
            row.put("reason", handoff.getReason());
            row.put("summary", handoff.getSummary());
            row.put("status", handoff.getStatus());
            row.put("createdAt", handoff.getCreatedAt().toString());
            row.put("userName", user != null ? user.getNickname() : "顾客");
            row.put("userPhone", user != null ? user.getPhone() : "");
            rows.add(row);
        }
        return Result.ok(rows);
    }

    /** 工单详情：顾客信息 + 最近对话原文（前台看得懂的那一版） */
    @GetMapping("/handoffs/{id}/detail")
    public Result<Map<String, Object>> handoffDetail(@PathVariable Long id) {
        AiHandoff handoff = handoffMapper.selectById(id);
        if (handoff == null) {
            return Result.ok(Map.of());
        }
        User user = userMapper.selectById(handoff.getUserId());
        List<AiMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getSessionId, handoff.getSessionId())
                .in(AiMessage::getRole, "USER", "ASSISTANT")
                .orderByAsc(AiMessage::getId)
                .last("LIMIT 20"));
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("handoff", handoff);
        result.put("userName", user != null ? user.getNickname() : "顾客");
        result.put("userPhone", user != null ? user.getPhone() : "");
        result.put("messages", messages);
        return Result.ok(result);
    }

    @PostMapping("/handoffs/{id}/resolve")
    public Result<Void> resolve(@PathVariable Long id) {
        AiHandoff handoff = handoffMapper.selectById(id);
        if (handoff != null) {
            handoff.setStatus("RESOLVED");
            handoff.setResolvedAt(LocalDateTime.now());
            handoffMapper.updateById(handoff);
        }
        return Result.ok();
    }

    @PostMapping("/evals/run")
    public Result<AiEvalRun> runEval() {
        return Result.ok(evalService.run(evalService.evalUser().userId()));
    }

    @GetMapping("/evals")
    public Result<List<AiEvalRun>> evals() {
        return Result.ok(evalService.runs());
    }
}
