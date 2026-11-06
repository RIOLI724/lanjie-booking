package com.lanjie.booking.ai;

import java.util.List;

/**
 * 确定性 mock 模型：按关键词规划工具调用，供 CI 与评测使用。
 * 真模型的「智能」在 GLM；mock 保证同一输入永远走同一路径，评测可回归。
 */
public class MockChatModel implements ChatModel {

    @Override
    public ChatReply chat(List<ChatMsg> messages, List<ToolSpec> tools) {
        String lastUser = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                lastUser = messages.get(i).content();
                break;
            }
        }
        // 工具执行结果回填后，直接给最终答复
        ChatMsg last = messages.get(messages.size() - 1);
        if ("tool".equals(last.role())) {
            String result = last.content();
            return new ChatReply(finalAnswer(lastUser, result), List.of(), 0, 0);
        }

        String text = lastUser == null ? "" : lastUser.trim();
        if (text.isEmpty()) {
            return new ChatReply("你好，我是篮界 AI 前台。想订场、约教练，还是问规则？", List.of(), 0, 0);
        }

        String tool = planTool(text);
        if (tool == null) {
            return new ChatReply("这个我帮不上忙，已为你转前台。", List.of(), 0, 0);
        }
        String args = argsFor(tool, text);
        return new ChatReply("", List.of(new ToolCall("call_" + Math.abs(text.hashCode()), tool, args)), 0, 0);
    }

    static String planTool(String text) {
        if (containsAny(text, "忽略", "ignore", "免费", "系统提示", "prompt", "绕过", "越狱")) {
            return "handoff_to_human"; // 注入：直接转人工，不执行任何副作用
        }
        if (containsAny(text, "投诉", "人工", "真人", "找前台", "老板")) {
            return "handoff_to_human";
        }
        if (containsAny(text, "帮我退", "我要退", "退款到账", "退钱", "退到")) {
            return "handoff_to_human"; // 钱相关：转人工
        }
        if (containsAny(text, "退", "取消")) {
            return "get_policy";
        }
        if (containsAny(text, "改期", "换时间")) {
            return "get_policy";
        }
        if (containsAny(text, "爽约", "违约", "没去")) {
            return "get_policy";
        }
        if (containsAny(text, "课包", "课时", "几节")) {
            return "list_my_packages";
        }
        if (containsAny(text, "团课")) {
            return "list_group_classes";
        }
        if (containsAny(text, "拼场")) {
            return "get_policy";
        }
        if (containsAny(text, "我的订单", "查订单", "订单")) {
            return "list_my_bookings";
        }
        if (containsAny(text, "教练", "私教")) {
            return "list_coaches";
        }
        if (containsAny(text, "停车", "地址", "营业", "几点", "电话", "在哪")) {
            return "get_venue_info";
        }
        if (containsAny(text, "空场", "有场", "订场", "想打", "场地", "今晚", "明天", "周六", "周日", "周五", "下午", "晚上")) {
            return "search_slots";
        }
        if (containsAny(text, "规则", "政策", "怎么算")) {
            return "get_policy";
        }
        return "handoff";
    }

    static String argsFor(String tool, String text) {
        if ("get_policy".equals(tool)) {
            String topic = containsAny(text, "改期") ? "reschedule"
                    : containsAny(text, "爽约", "违约") ? "noshow"
                    : containsAny(text, "课包", "课时") ? "package"
                    : containsAny(text, "团课") ? "group"
                    : containsAny(text, "拼场") ? "pickup"
                    : "refund";
            return "{\"topic\":\"" + topic + "\"}";
        }
        if ("search_slots".equals(tool)) {
            String date = containsAny(text, "今晚") ? "today"
                    : containsAny(text, "明天") ? "tomorrow"
                    : containsAny(text, "周六", "周日", "周五") ? "weekend"
                    : "today";
            return "{\"dateHint\":\"" + date + "\"}";
        }
        if ("handoff_to_human".equals(tool)) {
            String reason = containsAny(text, "忽略", "ignore", "免费", "系统提示", "prompt", "绕过", "越狱")
                    ? "OUT_OF_SCOPE"
                    : containsAny(text, "投诉") ? "COMPLAINT"
                    : containsAny(text, "帮我退", "我要退", "退款到账", "退钱", "退到") ? "PAYMENT"
                    : "USER_REQUEST";
            return "{\"reason\":\"" + reason + "\",\"summary\":\"" + text.replace("\"", "'") + "\"}";
        }
        return "{}";
    }

    private String finalAnswer(String question, String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return "没查到相关信息，已为你转前台。";
        }
        if (toolResult.contains("\"handoff\":true")) {
            return "好的，已为你转前台，请稍候。";
        }
        if (toolResult.contains("\"policies\"")) {
            return "按场馆规则：" + trim(toolResult) + "\n如需办理，我可以帮你转前台。";
        }
        if (toolResult.contains("\"slots\"")) {
            return "给你找到这些空场：" + trim(toolResult) + "\n要哪个时段？我可以先帮你锁 15 分钟。";
        }
        if (toolResult.contains("\"coaches\"")) {
            return "可以约这些教练：" + trim(toolResult);
        }
        if (toolResult.contains("\"bookings\"")) {
            return "你的订单：" + trim(toolResult);
        }
        if (toolResult.contains("\"venue\"")) {
            return trim(toolResult);
        }
        return trim(toolResult);
    }

    private String trim(String text) {
        String cleaned = text.replaceAll("\\s+", " ");
        return cleaned.length() > 300 ? cleaned.substring(0, 300) + "…" : cleaned;
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String modelName() {
        return "mock";
    }
}
