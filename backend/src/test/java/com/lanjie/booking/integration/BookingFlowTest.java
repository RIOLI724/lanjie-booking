package com.lanjie.booking.integration;

import com.jayway.jsonpath.JsonPath;
import com.lanjie.booking.entity.Slot;
import com.lanjie.booking.mapper.SlotMapper;
import com.lanjie.booking.scheduler.ReleaseScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W3 集成测试：锁位 → 支付 → 状态机的全链路，以及并发抢场、幂等、超时释放、日限。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookingFlowTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private SlotMapper slotMapper;
    @Autowired
    private ReleaseScheduler releaseScheduler;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void twoWindowsRaceForSameSlot() {
        String userA = registerAndLogin();
        String userB = registerAndLogin();
        Slot slot = firstAvailableSlot(1);

        ResponseEntity<String> holdA = hold(userA, List.of(slot.getId()));
        assertEquals(0, code(holdA));
        long bookingA = readLong(holdA.getBody(), "$.data.bookingId");
        assertEquals("HELD", slotStatus(slot.getId()));

        // 第二个窗口抢同一场：只允许一个成功
        ResponseEntity<String> holdB = hold(userB, List.of(slot.getId()));
        assertEquals(40901, code(holdB));

        // 支付 → 确认 → 格子 BOOKED
        ResponseEntity<String> pay = pay(userA, bookingA);
        assertEquals(0, code(pay));
        String payNo = JsonPath.read(pay.getBody(), "$.data.payNo");
        assertEquals(0, code(confirm(payNo, userA)));
        assertEquals("BOOKED", slotStatus(slot.getId()));
        assertEquals("CONFIRMED", bookingStatus(bookingA));

        // 回调重放：幂等成功，不产生第二笔支付
        assertEquals(0, code(confirm(payNo, userA)));
        Integer paidCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pay_order WHERE booking_id = ? AND status = 'PAID'", Integer.class, bookingA);
        assertEquals(1, paidCount);
    }

    @Test
    void idempotencyKeyReplaysSameHold() {
        String token = registerAndLogin();
        Slot slot = firstAvailableSlot(2);
        String key = "test-" + UUID.randomUUID();

        ResponseEntity<String> first = holdWithKey(token, List.of(slot.getId()), key);
        assertEquals(0, code(first));
        long bookingId = readLong(first.getBody(), "$.data.bookingId");

        ResponseEntity<String> replay = holdWithKey(token, List.of(slot.getId()), key);
        assertEquals(0, code(replay));
        assertEquals(bookingId, ((Number) JsonPath.read(replay.getBody(), "$.data.bookingId")).longValue());
        Integer bookingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM booking WHERE user_id = (SELECT user_id FROM booking WHERE id = ?)",
                Integer.class, bookingId);
        assertEquals(1, bookingCount);

        // 清理：取消释放
        assertEquals(0, code(post("/api/v1/bookings/" + bookingId + "/cancel", Map.of(), token)));
        assertEquals("AVAILABLE", slotStatus(slot.getId()));
    }

    @Test
    void holdValidation() {
        String token = registerAndLogin();
        Slot slot = firstAvailableSlot(1);
        Slot otherCourt = firstAvailableSlot(2);

        // 跨场 → 40902
        assertEquals(40902, code(hold(token, List.of(slot.getId(), otherCourt.getId()))));
        // 同场不连续：09:00 与 11:00 → 40902
        Slot plus2 = slotMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Slot>()
                .eq(Slot::getCourtId, slot.getCourtId())
                .eq(Slot::getSlotDate, slot.getSlotDate())
                .eq(Slot::getStatus, com.lanjie.booking.enums.SlotStatus.AVAILABLE)
                .gt(Slot::getStartAt, slot.getStartAt().plusHours(1).plusMinutes(30))
                .last("LIMIT 1")).get(0);
        assertEquals(40902, code(hold(token, List.of(slot.getId(), plus2.getId()))));
        // 超 4 格 → 40000
        List<Long> five = slotMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Slot>()
                .eq(Slot::getCourtId, 3)
                .eq(Slot::getSlotDate, slot.getSlotDate())
                .eq(Slot::getStatus, com.lanjie.booking.enums.SlotStatus.AVAILABLE)
                .orderByAsc(Slot::getStartAt)
                .last("LIMIT 5"))
                .stream().map(Slot::getId).toList();
        assertEquals(40000, code(hold(token, five)));
    }

    @Test
    void expiredHoldIsSwept() {
        String token = registerAndLogin();
        Slot slot = firstAvailableSlot(4);
        ResponseEntity<String> holdResponse = hold(token, List.of(slot.getId()));
        assertEquals(0, code(holdResponse));
        long bookingId = readLong(holdResponse.getBody(), "$.data.bookingId");

        jdbcTemplate.update("UPDATE booking SET hold_expires_at = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id = ?", bookingId);
        releaseScheduler.sweep();

        assertEquals("CANCELLED", bookingStatus(bookingId));
        assertEquals("AVAILABLE", slotStatus(slot.getId()));
    }

    @Test
    void dailyBookingLimitIsEnforced() {
        String token = registerAndLogin();
        List<Slot> slots = slotMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Slot>()
                .eq(Slot::getCourtId, 3)
                .eq(Slot::getSlotDate, LocalDate.now().plusDays(1))
                .eq(Slot::getStatus, com.lanjie.booking.enums.SlotStatus.AVAILABLE)
                .gt(Slot::getStartAt, java.time.LocalDateTime.now())
                .orderByAsc(Slot::getStartAt)
                .last("LIMIT 2"));
        assertEquals(2, slots.size());

        for (Slot slot : slots) {
            ResponseEntity<String> holdResponse = hold(token, List.of(slot.getId()));
            assertEquals(0, code(holdResponse));
            long bookingId = readLong(holdResponse.getBody(), "$.data.bookingId");
            String payNo = JsonPath.read(pay(token, bookingId).getBody(), "$.data.payNo");
            assertEquals(0, code(confirm(payNo, token)));
        }
        // 第三单：拒绝
        Slot another = slotMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Slot>()
                .eq(Slot::getCourtId, 3)
                .eq(Slot::getSlotDate, LocalDate.now().plusDays(1))
                .eq(Slot::getStatus, com.lanjie.booking.enums.SlotStatus.AVAILABLE)
                .gt(Slot::getStartAt, java.time.LocalDateTime.now())
                .orderByAsc(Slot::getStartAt)
                .last("LIMIT 1")).get(0);
        assertEquals(40905, code(hold(token, List.of(another.getId()))));
    }

    // ---- helpers ----

    private long readLong(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }

    private Slot firstAvailableSlot(int courtId) {
        return slotMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Slot>()
                .eq(Slot::getCourtId, courtId)
                .eq(Slot::getSlotDate, LocalDate.now().plusDays(1))
                .eq(Slot::getStatus, com.lanjie.booking.enums.SlotStatus.AVAILABLE)
                .gt(Slot::getStartAt, java.time.LocalDateTime.now())
                .orderByAsc(Slot::getStartAt)
                .last("LIMIT 1")).get(0);
    }

    private String slotStatus(long slotId) {
        return jdbcTemplate.queryForObject("SELECT status FROM slot WHERE id = ?", String.class, slotId);
    }

    private String bookingStatus(long bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, bookingId);
    }

    private String registerAndLogin() {
        String phone = "135" + String.format("%08d", Math.abs(System.nanoTime()) % 100_000_000);
        assertEquals(0, code(post("/api/v1/auth/sms-code", Map.of("phone", phone, "scene", "REGISTER"), null)));
        ResponseEntity<String> register = post("/api/v1/auth/register",
                Map.of("phone", phone, "code", "123456"), null);
        assertEquals(0, code(register));
        return JsonPath.read(register.getBody(), "$.data.accessToken");
    }

    private ResponseEntity<String> hold(String token, List<Long> slotIds) {
        return holdWithKey(token, slotIds, "hold-" + UUID.randomUUID());
    }

    private ResponseEntity<String> holdWithKey(String token, List<Long> slotIds, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", key);
        return restTemplate.exchange("/api/v1/bookings/holds", HttpMethod.POST,
                new HttpEntity<>(Map.of("slotIds", slotIds), headers), String.class);
    }

    private ResponseEntity<String> pay(String token, long bookingId) {
        return post("/api/v1/bookings/" + bookingId + "/pay", Map.of("channel", "WECHAT_MOCK"), token);
    }

    private ResponseEntity<String> confirm(String payNo, String token) {
        return post("/api/v1/payments/mock/" + payNo + "/confirm", Map.of(), token);
    }

    private ResponseEntity<String> post(String path, Map<String, ?> body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private int code(ResponseEntity<String> response) {
        assertTrue(response.getStatusCode().is2xxSuccessful(), () -> "http " + response.getStatusCode());
        return (int) JsonPath.read(response.getBody(), "$.code");
    }
}
