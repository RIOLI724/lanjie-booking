package com.lanjie.booking.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.dto.CoachHoldRequest;
import com.lanjie.booking.dto.HoldRequest;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.service.BookingService;
import com.lanjie.booking.service.CoachBookingService;
import com.lanjie.booking.vo.HoldVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户确认凭证：模型只能「提案」，确认 token 由用户点击确认后兑换执行；
 * 5 分钟有效、一次性、绑定用户（Redis SETNX + DEL）。
 */
@Service
public class AiConfirmationService {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final BookingService bookingService;
    private final CoachBookingService coachBookingService;

    public AiConfirmationService(StringRedisTemplate redis, ObjectMapper objectMapper,
                                 BookingService bookingService, CoachBookingService coachBookingService) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.bookingService = bookingService;
        this.coachBookingService = coachBookingService;
    }

    public String propose(Long userId, String tool, Object payload) {
        String token = UUID.randomUUID().toString().replace("-", "");
        try {
            Map<String, Object> data = Map.of("userId", userId, "tool", tool,
                    "payload", objectMapper.writeValueAsString(payload));
            redis.opsForValue().set("lanjie:ai:confirm:" + token,
                    objectMapper.writeValueAsString(data), TTL);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }
        return token;
    }

    /** 用户确认后执行；一次性，重复兑换直接拒绝 */
    public HoldVO execute(Long userId, String token) {
        String key = "lanjie:ai:confirm:" + token;
        String raw = redis.opsForValue().get(key);
        if (raw == null) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "确认已过期或已使用");
        }
        redis.delete(key);
        try {
            var node = objectMapper.readTree(raw);
            if (node.path("userId").asLong() != userId) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            String tool = node.path("tool").asText();
            var payload = objectMapper.readTree(node.path("payload").asText());
            LoginUser loginUser = new LoginUser(userId, null, "CUSTOMER");
            if ("HOLD_SLOTS".equals(tool)) {
                HoldRequest request = new HoldRequest();
                List<Long> slotIds = new java.util.ArrayList<>();
                payload.path("slotIds").forEach(id -> slotIds.add(id.asLong()));
                request.setSlotIds(slotIds);
                return bookingService.hold(loginUser, request);
            }
            if ("HOLD_COACH".equals(tool)) {
                CoachHoldRequest request = new CoachHoldRequest();
                request.setCoachSlotId(payload.path("coachSlotId").asLong());
                request.setServiceId(payload.path("serviceId").asLong());
                if (payload.has("courtSlotId")) {
                    request.setCourtSlotId(payload.path("courtSlotId").asLong());
                }
                return coachBookingService.hold(loginUser, request);
            }
            throw new BusinessException(ErrorCode.PARAM_INVALID, "未知确认动作");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }
    }
}
