package com.lanjie.booking.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 模型选择：AI_PROVIDER=glm 且配置 GLM_API_KEY 时用真模型，否则 mock（CI/离线可跑） */
@Configuration
public class AiConfig {

    @Bean
    public ChatModel chatModel(@Value("${lanjie.ai.provider:mock}") String provider,
                               @Value("${lanjie.ai.glm.api-key:}") String apiKey,
                               @Value("${lanjie.ai.glm.model:glm-4-flash}") String model,
                               @Value("${lanjie.ai.glm.endpoint:https://open.bigmodel.cn/api/paas/v4/chat/completions}")
                               String endpoint) {
        if ("glm".equalsIgnoreCase(provider) && apiKey != null && !apiKey.isBlank()) {
            return new GlmChatModel(apiKey, model, endpoint);
        }
        return new MockChatModel();
    }
}
