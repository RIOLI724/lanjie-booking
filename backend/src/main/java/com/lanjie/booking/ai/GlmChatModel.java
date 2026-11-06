package com.lanjie.booking.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 智谱 GLM（OpenAI 兼容）客户端：零额外依赖，直接 HTTP。
 * 仅当配置了 GLM_API_KEY 时启用；失败由上层重试/降级为转人工。
 */
@Slf4j
public class GlmChatModel implements ChatModel {

    private final String apiKey;
    private final String model;
    private final String endpoint;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public GlmChatModel(String apiKey, String model, String endpoint) {
        this.apiKey = apiKey;
        this.model = model;
        this.endpoint = endpoint;
    }

    @Override
    public ChatReply chat(List<ChatMsg> messages, List<ToolSpec> tools) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            ArrayNode msgs = body.putArray("messages");
            for (ChatMsg msg : messages) {
                ObjectNode node = msgs.addObject();
                node.put("role", msg.role());
                node.put("content", msg.content() == null ? "" : msg.content());
                if (msg.rawToolCalls() != null) {
                    node.set("tool_calls", objectMapper.readTree(msg.rawToolCalls()));
                }
                if (msg.toolCallId() != null) {
                    node.put("tool_call_id", msg.toolCallId());
                }
            }
            if (tools != null && !tools.isEmpty()) {
                ArrayNode toolArr = body.putArray("tools");
                for (ToolSpec spec : tools) {
                    ObjectNode tool = toolArr.addObject();
                    tool.put("type", "function");
                    ObjectNode fn = tool.putObject("function");
                    fn.put("name", spec.name());
                    fn.put("description", spec.description());
                    fn.set("parameters", objectMapper.readTree(spec.parametersJson()));
                }
                body.put("tool_choice", "auto");
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("glm http {}: {}", response.statusCode(), response.body());
                throw new IllegalStateException("GLM 调用失败: " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choice = root.path("choices").path(0).path("message");
            String text = choice.path("content").asText("");
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode call : choice.path("tool_calls")) {
                calls.add(new ToolCall(call.path("id").asText(),
                        call.path("function").path("name").asText(),
                        call.path("function").path("arguments").asText("{}")));
            }
            int tokensIn = root.path("usage").path("prompt_tokens").asInt(0);
            int tokensOut = root.path("usage").path("completion_tokens").asInt(0);
            return new ChatReply(text, calls, tokensIn, tokensOut);
        } catch (Exception e) {
            throw new IllegalStateException("GLM 调用异常", e);
        }
    }

    @Override
    public String modelName() {
        return model;
    }
}
