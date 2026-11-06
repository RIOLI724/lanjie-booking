package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanjie.booking.ai.ChatModel;
import com.lanjie.booking.entity.AiEvalCase;
import com.lanjie.booking.entity.AiEvalResult;
import com.lanjie.booking.entity.AiEvalRun;
import com.lanjie.booking.entity.AiSession;
import com.lanjie.booking.entity.AiToolCall;
import com.lanjie.booking.mapper.AiEvalCaseMapper;
import com.lanjie.booking.mapper.AiEvalResultMapper;
import com.lanjie.booking.mapper.AiEvalRunMapper;
import com.lanjie.booking.mapper.AiToolCallMapper;
import com.lanjie.booking.mapper.UserMapper;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.security.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 评测：40 条金标集在 mock 模型下确定性回归（CI 门禁 ≥90%，注入 100% 拦截）。
 * 真模型评测把同一个 runner 指向 glm 即可，指标口径完全一致。
 */
@Slf4j
@Service
public class AiEvalService {

    private final AiEvalCaseMapper caseMapper;
    private final AiEvalRunMapper runMapper;
    private final AiEvalResultMapper resultMapper;
    private final AiToolCallMapper toolCallMapper;
    private final AiAgentService agentService;
    private final ChatModel chatModel;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    public AiEvalService(AiEvalCaseMapper caseMapper, AiEvalRunMapper runMapper,
                         AiEvalResultMapper resultMapper, AiToolCallMapper toolCallMapper,
                         AiAgentService agentService, ChatModel chatModel,
                         UserMapper userMapper, ObjectMapper objectMapper) {
        this.caseMapper = caseMapper;
        this.runMapper = runMapper;
        this.resultMapper = resultMapper;
        this.toolCallMapper = toolCallMapper;
        this.agentService = agentService;
        this.chatModel = chatModel;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
    }

    public AiEvalRun run(Long evalUserId) {
        AiEvalRun run = new AiEvalRun();
        run.setModel(chatModel.modelName());
        run.setPromptVersion("v1");
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);

        List<AiEvalCase> cases = caseMapper.selectList(new LambdaQueryWrapper<AiEvalCase>()
                .eq(AiEvalCase::getEnabled, true));
        int passed = 0;
        List<String> failures = new ArrayList<>();
        for (AiEvalCase evalCase : cases) {
            boolean ok = false;
            String tools = "";
            String answer = "";
            String error = null;
            try {
                LoginUser user = new LoginUser(evalUserId, null, "CUSTOMER");
                AiSession session = agentService.createSession(user);
                var result = agentService.chat(user, session.getId(), evalCase.getInput(), null);
                answer = result.answer();
                tools = String.join(",", toolCallMapper.selectList(new LambdaQueryWrapper<AiToolCall>()
                                .eq(AiToolCall::getSessionId, session.getId()))
                        .stream().map(AiToolCall::getToolName).toList());
                boolean toolsOk = true;
                if (evalCase.getExpectedTools() != null && !evalCase.getExpectedTools().isBlank()) {
                    for (String tool : evalCase.getExpectedTools().split(",")) {
                        if (!tool.isBlank() && !tools.contains(tool.trim())) {
                            toolsOk = false;
                            break;
                        }
                    }
                }
                boolean containsOk = true;
                if (evalCase.getExpectedContains() != null && !evalCase.getExpectedContains().isBlank()) {
                    containsOk = false;
                    for (String word : evalCase.getExpectedContains().split("\\|")) {
                        if (answer.contains(word.trim())) {
                            containsOk = true;
                            break;
                        }
                    }
                }
                boolean handoffOk = !Boolean.TRUE.equals(evalCase.getExpectedHandoff()) || result.handoff();
                ok = toolsOk && containsOk && handoffOk;
            } catch (Exception e) {
                error = e.getMessage();
            }
            AiEvalResult result = new AiEvalResult();
            result.setRunId(run.getId());
            result.setCaseId(evalCase.getId());
            result.setPassed(ok);
            result.setActualTools(tools);
            result.setActualAnswer(answer.length() > 500 ? answer.substring(0, 500) : answer);
            result.setError(error);
            resultMapper.insert(result);
            if (ok) {
                passed++;
            } else {
                failures.add(evalCase.getName());
            }
        }
        run.setTotal(cases.size());
        run.setPassed(passed);
        run.setFailed(cases.size() - passed);
        run.setFinishedAt(LocalDateTime.now());
        try {
            run.setReportJson(objectMapper.writeValueAsString(
                    java.util.Map.of("failures", failures)));
        } catch (Exception ignored) {
        }
        runMapper.updateById(run);
        log.info("ai eval finished: model={} {}/{}", run.getModel(), passed, cases.size());
        return run;
    }

    public List<AiEvalRun> runs() {
        return runMapper.selectList(new LambdaQueryWrapper<AiEvalRun>()
                .orderByDesc(AiEvalRun::getId).last("LIMIT 20"));
    }

    public AiEvalRun latest() {
        return runMapper.selectOne(new LambdaQueryWrapper<AiEvalRun>()
                .orderByDesc(AiEvalRun::getId).last("LIMIT 1"));
    }

    public LoginUser evalUser() {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, "13800000000").last("LIMIT 1"));
        return new LoginUser(user.getId(), user.getPhone(), "CUSTOMER");
    }
}
