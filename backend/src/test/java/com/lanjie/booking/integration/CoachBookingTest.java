package com.lanjie.booking.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jayway.jsonpath.JsonPath;
import com.lanjie.booking.entity.CoachPackage;
import com.lanjie.booking.entity.CoachSlot;
import com.lanjie.booking.entity.Slot;
import com.lanjie.booking.mapper.CoachPackageMapper;
import com.lanjie.booking.mapper.CoachSlotMapper;
import com.lanjie.booking.mapper.SlotMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W5 集成测试：教练档期、私教双资源锁、课包购买/核销/退回 */
class CoachBookingTest extends IntegrationTestBase {

    @Autowired
    private CoachSlotMapper coachSlotMapper;
    @Autowired
    private SlotMapper slotMapper;
    @Autowired
    private CoachPackageMapper coachPackageMapper;

    @Test
    void coachListedWithSlots() {
        String token = registerAndLogin();
        ResponseEntity<String> coaches = get("/api/v1/coaches", token);
        assertEquals(0, code(coaches));
        int count = ((Number) JsonPath.read(coaches.getBody(), "$.data.length()")).intValue();
        assertEquals(3, count);
        assertEquals("张教练", JsonPath.read(coaches.getBody(), "$.data[0].displayName"));

        long coachId = readLong(coaches.getBody(), "$.data[0].id");
        ResponseEntity<String> slots = get("/api/v1/coaches/" + coachId + "/slots?date="
                + LocalDate.now().plusDays(1), token);
        assertEquals(0, code(slots));
        int slotCount = ((Number) JsonPath.read(slots.getBody(), "$.data.length()")).intValue();
        assertEquals(13, slotCount);
    }

    @Test
    void privateBookingLocksCoachAndCourtTogether() {
        String userA = registerAndLogin();
        String userB = registerAndLogin();
        CoachSlot coachSlot = firstBookableCoachSlot(1);
        Slot courtSlot = courtSlotAt(coachSlot.getStartAt());

        // A：教练位 + 场地格一起锁
        ResponseEntity<String> holdA = coachHold(userA, coachSlot.getId(), courtSlot.getId(), null);
        assertEquals(0, code(holdA));
        long bookingA = readLong(holdA.getBody(), "$.data.bookingId");
        assertEquals("HELD", coachSlotStatus(coachSlot.getId()));
        assertEquals("HELD", slotStatus(courtSlot.getId()));

        // B 抢同一教练位 → 拒绝
        ResponseEntity<String> holdB = coachHold(userB, coachSlot.getId(), courtSlotAt(coachSlot.getStartAt()).getId(), null);
        assertEquals(40901, code(holdB));

        // 支付 → 双资源 BOOKED
        ResponseEntity<String> pay = post("/api/v1/bookings/" + bookingA + "/pay",
                Map.of("channel", "WECHAT_MOCK"), userA);
        assertEquals(0, code(pay));
        assertEquals(0, code(confirm(JsonPath.read(pay.getBody(), "$.data.payNo"), userA)));
        assertEquals("BOOKED", coachSlotStatus(coachSlot.getId()));
        assertEquals("BOOKED", slotStatus(courtSlot.getId()));
        assertEquals("CONFIRMED", bookingStatus(bookingA));
    }

    @Test
    void packagePurchaseConsumeAndRestore() {
        String token = registerAndLogin();

        // 充值 2000+400 → 钱包买 5 节课包（300×5×0.95 = ¥1425 = 142500 分）
        long planId = readLong(get("/api/v1/recharge-plans", token).getBody(), "$.data[2].id");
        String rechargeNo = JsonPath.read(post("/api/v1/wallet/recharge",
                Map.of("planId", planId, "channel", "WECHAT_MOCK"), token).getBody(), "$.data.payNo");
        assertEquals(0, code(confirm(rechargeNo, token)));
        assertEquals(240000, readLong(get("/api/v1/wallet", token).getBody(), "$.data.balanceCents"));

        ResponseEntity<String> purchase = post("/api/v1/coach-packages",
                Map.of("coachId", 1, "sessions", 5, "channel", "WALLET"), token);
        assertEquals(0, code(purchase));
        long packageId = ((Number) jdbcTemplate.queryForObject(
                "SELECT id FROM coach_package ORDER BY id DESC LIMIT 1", Integer.class)).longValue();
        assertEquals(240000 - 142500, readLong(get("/api/v1/wallet", token).getBody(), "$.data.balanceCents"));

        // 课包核销私教课：0 元确认
        CoachSlot coachSlot = firstBookableCoachSlot(1);
        Slot courtSlot = courtSlotAt(coachSlot.getStartAt());
        ResponseEntity<String> holdResponse = coachHold(token, coachSlot.getId(), courtSlot.getId(), packageId);
        assertEquals(0, code(holdResponse));
        long bookingId = readLong(holdResponse.getBody(), "$.data.bookingId");
        assertEquals(0, code(post("/api/v1/bookings/" + bookingId + "/pay",
                Map.of("channel", "PACKAGE", "packageId", packageId), token)));
        assertEquals("CONFIRMED", bookingStatus(bookingId));
        assertEquals(4, remaining(packageId));

        // ≥24h 取消 → 课时退回
        assertEquals(0, code(post("/api/v1/bookings/" + bookingId + "/cancel", Map.of(), token)));
        assertEquals(5, remaining(packageId));
        assertEquals("AVAILABLE", coachSlotStatus(coachSlot.getId()));
    }

    @Test
    void lateCoachCancelKeepsMoney() {
        String token = registerAndLogin();
        CoachSlot coachSlot = firstBookableCoachSlot(2);
        Slot courtSlot = courtSlotAt(coachSlot.getStartAt());
        long bookingId = holdAndPay(token, coachSlot.getId(), courtSlot.getId());
        // 场次挪到现在 1 小时后（<24h）
        jdbcTemplate.update(
                "UPDATE booking_item SET start_at = DATE_ADD(NOW(), INTERVAL 1 HOUR), "
                        + "end_at = DATE_ADD(NOW(), INTERVAL 2 HOUR) WHERE booking_id = ?", bookingId);
        assertEquals(0, code(post("/api/v1/bookings/" + bookingId + "/cancel", Map.of(), token)));
        assertEquals(0, refundAmount(bookingId));
        assertEquals("CANCELLED", bookingStatus(bookingId));
    }

    // ---- helpers ----

    /** 测试自清理 + 找一个「教练位与场地格都空闲」的档期 */
    private CoachSlot firstBookableCoachSlot(long coachId) {
        jdbcTemplate.update(
                "UPDATE coach_slot SET status = 'AVAILABLE', hold_token = NULL, hold_expires_at = NULL, "
                        + "owner_type = NULL, owner_id = NULL WHERE coach_id = ? "
                        + "AND DATE(start_at) = ? AND status IN ('HELD', 'BOOKED')",
                coachId, LocalDate.now().plusDays(1));
        List<CoachSlot> slots = coachSlotMapper.selectList(new LambdaQueryWrapper<CoachSlot>()
                .eq(CoachSlot::getCoachId, coachId)
                .eq(CoachSlot::getStatus, com.lanjie.booking.enums.SlotStatus.AVAILABLE)
                .gt(CoachSlot::getStartAt, LocalDateTime.now())
                .apply("DATE(start_at) = {0}", LocalDate.now().plusDays(1))
                .orderByAsc(CoachSlot::getStartAt));
        for (CoachSlot slot : slots) {
            List<Slot> courts = slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                    .eq(Slot::getStartAt, slot.getStartAt())
                    .eq(Slot::getStatus, com.lanjie.booking.enums.SlotStatus.AVAILABLE)
                    .orderByAsc(Slot::getCourtId)
                    .last("LIMIT 1"));
            if (!courts.isEmpty()) {
                return slot;
            }
        }
        throw new AssertionError("没有可同时锁教练位与场地格的档期");
    }

    private long serviceIdOf(long coachId) {
        ResponseEntity<String> coach = get("/api/v1/coaches/" + coachId, registerAndLogin());
        assertEquals(0, code(coach));
        int n = ((Number) JsonPath.read(coach.getBody(), "$.data.services.length()")).intValue();
        for (int i = 0; i < n; i++) {
            if ("PRIVATE".equals(JsonPath.read(coach.getBody(), "$.data.services[" + i + "].type"))) {
                return readLong(coach.getBody(), "$.data.services[" + i + "].id");
            }
        }
        throw new AssertionError("coach has no private service");
    }

    private Slot courtSlotAt(LocalDateTime startAt) {
        return slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                .eq(Slot::getStartAt, startAt)
                .eq(Slot::getStatus, com.lanjie.booking.enums.SlotStatus.AVAILABLE)
                .orderByAsc(Slot::getCourtId)
                .last("LIMIT 1")).get(0);
    }

    private String coachSlotStatus(long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM coach_slot WHERE id = ?", String.class, id);
    }

    private String slotStatus(long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM slot WHERE id = ?", String.class, id);
    }

    private String bookingStatus(long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, id);
    }

    private int remaining(long packageId) {
        return jdbcTemplate.queryForObject(
                "SELECT remaining_sessions FROM coach_package WHERE id = ?", Integer.class, packageId);
    }

    private long refundAmount(long bookingId) {
        Integer amount = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM refund_order WHERE booking_id=? AND status='SUCCESS'",
                Integer.class, bookingId);
        return amount;
    }

    private ResponseEntity<String> coachHold(String token, long coachSlotId, Long courtSlotId, Long packageId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("coachSlotId", coachSlotId);
        body.put("serviceId", serviceIdOf(coachSlotMapper.selectById(coachSlotId).getCoachId()));
        body.put("courtSlotId", courtSlotId);
        if (packageId != null) {
            body.put("packageId", packageId);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", "chold-" + UUID.randomUUID());
        return restTemplate.exchange("/api/v1/coach-bookings/holds", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private long holdAndPay(String token, long coachSlotId, Long courtSlotId) {
        ResponseEntity<String> holdResponse = coachHold(token, coachSlotId, courtSlotId, null);
        assertEquals(0, code(holdResponse));
        long bookingId = readLong(holdResponse.getBody(), "$.data.bookingId");
        ResponseEntity<String> pay = post("/api/v1/bookings/" + bookingId + "/pay",
                Map.of("channel", "WECHAT_MOCK"), token);
        assertEquals(0, code(pay));
        assertEquals(0, code(confirm(JsonPath.read(pay.getBody(), "$.data.payNo"), token)));
        return bookingId;
    }

    private String registerAndLogin() {
        String phone = "133" + String.format("%08d", Math.abs(System.nanoTime()) % 100_000_000);
        assertEquals(0, code(post("/api/v1/auth/sms-code", Map.of("phone", phone, "scene", "REGISTER"), null)));
        ResponseEntity<String> register = post("/api/v1/auth/register",
                Map.of("phone", phone, "code", "123456"), null);
        assertEquals(0, code(register));
        return JsonPath.read(register.getBody(), "$.data.accessToken");
    }

    private ResponseEntity<String> confirm(String payNo, String token) {
        return post("/api/v1/payments/mock/" + payNo + "/confirm", Map.of(), token);
    }

    private ResponseEntity<String> get(String path, String token) {
        return exchange(path, HttpMethod.GET, null, token);
    }

    private ResponseEntity<String> post(String path, Map<String, ?> body, String token) {
        return exchange(path, HttpMethod.POST, body, token);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, Map<String, ?> body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private int code(ResponseEntity<String> response) {
        assertTrue(response.getStatusCode().is2xxSuccessful(), () -> "http " + response.getStatusCode());
        return (int) JsonPath.read(response.getBody(), "$.code");
    }

    private long readLong(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }
}
