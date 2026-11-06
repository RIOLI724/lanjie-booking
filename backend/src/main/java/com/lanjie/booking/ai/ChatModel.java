package com.lanjie.booking.ai;

import java.util.List;

/** 模型抽象：mock（测试/CI，确定性）与 GLM（OpenAI 兼容）共用同一工具协议 */
public interface ChatModel {

    record ChatMsg(String role, String content, String toolName, String toolCallId, String rawToolCalls) {
        public static ChatMsg user(String content) {
            return new ChatMsg("user", content, null, null, null);
        }

        public static ChatMsg system(String content) {
            return new ChatMsg("system", content, null, null, null);
        }

        public static ChatMsg assistant(String content) {
            return new ChatMsg("assistant", content, null, null, null);
        }

        public static ChatMsg assistantWithTools(String content, String rawToolCalls) {
            return new ChatMsg("assistant", content, null, null, rawToolCalls);
        }

        public static ChatMsg tool(String toolCallId, String content) {
            return new ChatMsg("tool", content, null, toolCallId, null);
        }
    }

    record ToolSpec(String name, String description, String parametersJson) {
    }

    record ToolCall(String id, String name, String argsJson) {
    }

    record ChatReply(String text, List<ToolCall> toolCalls, int tokensIn, int tokensOut) {
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    ChatReply chat(List<ChatMsg> messages, List<ToolSpec> tools);

    String modelName();
}
