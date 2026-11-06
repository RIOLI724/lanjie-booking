package com.lanjie.booking.vo;

import java.util.List;
import java.util.Map;

public record AiChatResult(Long sessionId, String answer, List<Map<String, Object>> steps,
                           boolean handoff) {
}
