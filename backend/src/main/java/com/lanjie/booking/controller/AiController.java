package com.lanjie.booking.controller;

import com.lanjie.booking.common.Result;
import com.lanjie.booking.entity.AiMessage;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.service.AiAgentService;
import com.lanjie.booking.ai.AiConfirmationService;
import com.lanjie.booking.vo.AiChatResult;
import com.lanjie.booking.vo.HoldVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final AiAgentService aiAgentService;
    private final AiConfirmationService confirmationService;
    private final TaskExecutor taskExecutor;

    public AiController(AiAgentService aiAgentService, AiConfirmationService confirmationService,
                        @org.springframework.beans.factory.annotation.Qualifier("applicationTaskExecutor")
                        TaskExecutor taskExecutor) {
        this.aiAgentService = aiAgentService;
        this.confirmationService = confirmationService;
        this.taskExecutor = taskExecutor;
    }

    @PostMapping("/sessions")
    public Result<Map<String, Object>> createSession(@AuthenticationPrincipal LoginUser loginUser) {
        var session = aiAgentService.createSession(loginUser);
        return Result.ok(Map.of("sessionId", session.getId(), "model", session.getModel()));
    }

    @PostMapping("/sessions/{id}/messages")
    public Result<AiChatResult> message(@AuthenticationPrincipal LoginUser loginUser,
                                        @PathVariable Long id,
                                        @Valid @RequestBody ChatRequest request) {
        return Result.ok(aiAgentService.chat(loginUser, id, request.getContent(), null));
    }

    /** SSE 流式：逐条推工具步骤，最后推完整答复 */
    @PostMapping(value = "/sessions/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter messageStream(@AuthenticationPrincipal LoginUser loginUser,
                                    @PathVariable Long id,
                                    @Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(60_000L);
        taskExecutor.execute(() -> {
            try {
                AiChatResult result = aiAgentService.chat(loginUser, id, request.getContent(),
                        event -> send(emitter, "step", event));
                send(emitter, "message", Map.of("answer", result.answer(),
                        "handoff", result.handoff(), "sessionId", result.sessionId()));
                send(emitter, "done", Map.of("ok", true));
                emitter.complete();
            } catch (Exception e) {
                send(emitter, "error", Map.of("message", "服务异常，请稍后再试"));
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
            // 客户端断开
        }
    }

    /** 用户点击确认卡：兑换一次性确认凭证，真正执行锁位 */
    @PostMapping("/confirmations/{token}")
    public Result<HoldVO> confirm(@AuthenticationPrincipal LoginUser loginUser, @PathVariable String token) {
        return Result.ok(confirmationService.execute(loginUser.userId(), token));
    }

    @PostMapping("/sessions/{id}/handoff")
    public Result<Void> handoff(@AuthenticationPrincipal LoginUser loginUser, @PathVariable Long id,
                                @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", "USER_REQUEST") : "USER_REQUEST";
        String summary = body != null ? body.getOrDefault("summary", "") : "";
        aiAgentService.handoffRequested(loginUser, id, reason, summary);
        return Result.ok();
    }

    @GetMapping("/sessions/{id}")
    public Result<List<AiMessage>> history(@AuthenticationPrincipal LoginUser loginUser, @PathVariable Long id) {
        return Result.ok(aiAgentService.history(loginUser, id));
    }

    @Data
    public static class ChatRequest {
        @NotBlank(message = "消息不能为空")
        private String content;
    }
}
