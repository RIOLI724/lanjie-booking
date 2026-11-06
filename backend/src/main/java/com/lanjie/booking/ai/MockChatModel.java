package com.lanjie.booking.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 确定性 mock 模型：按关键词规划工具调用，供 CI 与评测使用。
 * 最终回答必须是**人话**（从工具结果提炼），绝不把原始 JSON 当回答。
 */
public class MockChatModel implements ChatModel {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public ChatReply chat(List<ChatMsg> messages, List<ToolSpec> tools) {
        String lastUser = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                lastUser = messages.get(i).content();
                break;
            }
        }
        ChatMsg last = messages.get(messages.size() - 1);
        if ("tool".equals(last.role())) {
            return new ChatReply(finalAnswer(lastUser, last.content()), List.of(), 0, 0);
        }

        String text = lastUser == null ? "" : lastUser.trim();
        if (text.isEmpty()) {
            return new ChatReply("你好，我是篮界 AI 前台。想订场、约教练，还是问规则？", List.of(), 0, 0);
        }
        String tool = planTool(text);
        return new ChatReply("", List.of(new ToolCall("call_" + Math.abs(text.hashCode()), tool, argsFor(tool, text))), 0, 0);
    }

    static String planTool(String text) {
        if (containsAny(text, "忽略", "ignore", "免费", "系统提示", "prompt", "绕过", "越狱")) {
            return "handoff_to_human";
        }
        if (containsAny(text, "投诉", "人工", "真人", "找前台", "老板")) {
            return "handoff_to_human";
        }
        if (containsAny(text, "帮我退", "我要退", "退款到账", "退钱", "退到")) {
            return "handoff_to_human";
        }
        boolean policyHint = containsAny(text, "规则", "怎么", "怎么算", "多少", "多久", "有效", "几人", "几个", "能退", "优惠", "政策");
        if (containsAny(text, "退", "取消", "改期", "换时间")) {
            return "get_policy";
        }
        if (containsAny(text, "爽约", "违约", "没去")) {
            return "get_policy";
        }
        if (containsAny(text, "课包", "课时", "几节")) {
            return policyHint ? "get_policy" : "list_my_packages";
        }
        if (containsAny(text, "团课")) {
            return policyHint ? "get_policy" : "list_group_classes";
        }
        if (containsAny(text, "拼场", "优惠", "会员")) {
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
        if (containsAny(text, "空场", "有场", "订场", "想打", "场地", "全场", "订一", "打球",
                "今晚", "明天", "明晚", "今儿", "周六", "周日", "周五", "下午", "晚上", "周末")) {
            return "search_slots";
        }
        return "handoff_to_human";
    }

    static String argsFor(String tool, String text) {
        if ("get_policy".equals(tool)) {
            String topic = containsAny(text, "改期") ? "reschedule"
                    : containsAny(text, "爽约", "违约") ? "noshow"
                    : containsAny(text, "课包", "课时", "几节") ? "package"
                    : containsAny(text, "团课") ? "group"
                    : containsAny(text, "拼场") ? "pickup"
                    : containsAny(text, "优惠", "会员") ? "member"
                    : containsAny(text, "营业", "地址", "停车", "几点") ? "venue"
                    : "refund";
            return "{\"topic\":\"" + topic + "\"}";
        }
        if ("search_slots".equals(tool)) {
            String date = containsAny(text, "今儿", "今晚") ? "today"
                    : containsAny(text, "明天", "明晚") ? "tomorrow"
                    : containsAny(text, "周末", "周六", "周日", "周五") ? "weekend"
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
        try {
            JsonNode root = mapper.readTree(toolResult);
            if (root.has("handoff")) {
                return "好的，已为你转前台，请稍候。";
            }
            if (root.has("error")) {
                return "这个我没办成，帮你转前台处理。";
            }
            if (root.has("policies")) {
                JsonNode list = root.path("policies");
                if (list.isEmpty()) {
                    return "这个问题我查不到规则，不敢乱说，帮你转前台。";
                }
                return "按场馆规则：" + list.get(0).path("content").asText()
                        + "（依据：" + list.get(0).path("title").asText() + "）";
            }
            if (root.has("slots")) {
                JsonNode slots = root.path("slots");
                if (slots.isEmpty()) {
                    return "这个时段暂时没有空场，换个时间试试？";
                }
                StringBuilder sb = new StringBuilder("找到这些空场：");
                for (int i = 0; i < Math.min(slots.size(), 3); i++) {
                    JsonNode s = slots.get(i);
                    sb.append("\n").append(s.path("title").asText())
                            .append(" ¥").append(s.path("priceCents").asInt() / 100);
                }
                return sb.append("\n要哪个？我可以先帮你锁 15 分钟。").toString();
            }
            if (root.has("coaches")) {
                JsonNode coaches = root.path("coaches");
                StringBuilder sb = new StringBuilder("可以约这些教练：");
                for (int i = 0; i < Math.min(coaches.size(), 3); i++) {
                    JsonNode c = coaches.get(i);
                    sb.append("\n").append(c.path("displayName").asText())
                            .append(" · ").append(c.path("specialties").asText())
                            .append(" · ¥").append(c.path("basePriceCents").asInt() / 100).append("/节");
                }
                return sb.toString();
            }
            if (root.has("packages")) {
                JsonNode packages = root.path("packages");
                if (packages.isEmpty()) {
                    return "你还没有课包。私教有 5/10/20 节包，分别 95/90/85 折，12 个月有效。";
                }
                StringBuilder sb = new StringBuilder("你的课包：");
                for (int i = 0; i < Math.min(packages.size(), 3); i++) {
                    JsonNode p = packages.get(i);
                    sb.append("\n").append(p.path("coachName").asText())
                            .append(" 剩 ").append(p.path("remainingSessions").asInt()).append(" 节");
                }
                return sb.toString();
            }
            if (root.has("classes")) {
                JsonNode classes = root.path("classes");
                if (classes.isEmpty()) {
                    return "这天没有排团课，换个日期看看？";
                }
                StringBuilder sb = new StringBuilder("这天有这些团课：");
                for (int i = 0; i < Math.min(classes.size(), 3); i++) {
                    JsonNode c = classes.get(i);
                    sb.append("\n").append(c.path("title").asText())
                            .append(" · ").append(c.path("enrolled").asInt()).append("/")
                            .append(c.path("max").asInt()).append(" 人 · ¥")
                            .append(c.path("priceCents").asInt() / 100).append("/人");
                }
                return sb.toString();
            }
            if (root.has("bookings")) {
                JsonNode bookings = root.path("bookings");
                if (bookings.isEmpty()) {
                    return "你最近还没有订单。";
                }
                StringBuilder sb = new StringBuilder("你最近的订单：");
                for (int i = 0; i < Math.min(bookings.size(), 3); i++) {
                    JsonNode b = bookings.get(i);
                    sb.append("\n").append(b.path("title").asText())
                            .append(" · ").append(statusText(b.path("status").asText()));
                }
                return sb.toString();
            }
            if (root.has("venue")) {
                JsonNode venue = root.path("venue");
                return venue.path("name").asText() + "，营业时间 "
                        + venue.path("openTime").asText() + "–" + venue.path("closeTime").asText()
                        + "，地址 " + venue.path("address").asText()
                        + "，电话 " + venue.path("phone").asText() + "。";
            }
            return "我查到了，但这项还说不清楚，帮你转前台。";
        } catch (Exception e) {
            return "这个我没办成，帮你转前台处理。";
        }
    }

    private String statusText(String status) {
        return switch (status) {
            case "PENDING_PAYMENT" -> "待支付";
            case "CONFIRMED" -> "已支付";
            case "COMPLETED" -> "已完成";
            case "CANCELLED" -> "已取消";
            case "NO_SHOW" -> "爽约";
            default -> status;
        };
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
