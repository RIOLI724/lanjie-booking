package com.lanjie.booking.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W13 质量冲刺：并发抢场、越权隔离、E2E 旅程 */
class QualitySprintTest extends IntegrationTestBase {

    @Test
    void concurrentHoldOnlyOneWins() throws Exception {
        long slotId = jdbcTemplate.queryForObject(
                "SELECT id FROM slot WHERE slot_date = DATE_ADD(CURDATE(), INTERVAL 4 DAY) "
                        + "AND HOUR(start_at) = 10 AND status = 'AVAILABLE' LIMIT 1", Long.class);
        int racers = 6;
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            tokens.add(registerAndLogin());
        }
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(racers);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        for (String token : tokens) {
            pool.execute(() -> {
                try {
                    start.await();
                    int code = code(hold(token, slotId));
                    if (code == 0) {
                        success.incrementAndGet();
                    } else if (code == 40901) {
                        conflict.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(1, success.get(), "同一格子只能有一个锁位成功");
        assertEquals(racers - 1, conflict.get(), "其余请求应全部 40901");
        assertEquals("HELD", jdbcTemplate.queryForObject(
                "SELECT status FROM slot WHERE id = ?", String.class, slotId));
    }

    @Test
    void usersCannotTouchOthersData() {
        String userA = registerAndLogin();
        String userB = registerAndLogin();
        long slot = jdbcTemplate.queryForObject(
                "SELECT id FROM slot WHERE slot_date = DATE_ADD(CURDATE(), INTERVAL 4 DAY) "
                        + "AND HOUR(start_at) = 11 AND status = 'AVAILABLE' LIMIT 1", Long.class);
        ResponseEntity<String> holdB = hold(userB, slot);
        assertEquals(0, code(holdB));
        long bookingB = readLong(holdB.getBody(), "$.data.bookingId");

        // A 查 B 的订单 → 不存在
        assertEquals(40401, code(get("/api/v1/bookings/" + bookingB, userA)));
        // A 取消 B 的订单 → 不存在
        assertEquals(40401, code(post("/api/v1/bookings/" + bookingB + "/cancel", Map.of(), userA)));
        // A 拿 B 的核销码 → 不存在
        assertEquals(40401, code(get("/api/v1/bookings/" + bookingB + "/checkin-token", userA)));
        // 普通用户进管理端 → 403
        ResponseEntity<String> admin = get("/api/v1/admin/dashboard", userA);
        assertEquals(403, admin.getStatusCode().value());
    }

    @Test
    void endToEndJourneyRegisterPayCheckin() {
        String token = registerAndLogin();
        long slot = jdbcTemplate.queryForObject(
                "SELECT id FROM slot WHERE slot_date = DATE_ADD(CURDATE(), INTERVAL 4 DAY) "
                        + "AND HOUR(start_at) = 12 AND status = 'AVAILABLE' LIMIT 1", Long.class);

        ResponseEntity<String> hold = hold(token, slot);
        assertEquals(0, code(hold));
        long bookingId = readLong(hold.getBody(), "$.data.bookingId");

        ResponseEntity<String> pay = post("/api/v1/bookings/" + bookingId + "/pay",
                Map.of("channel", "WECHAT_MOCK"), token);
        assertEquals(0, code(pay));
        String payNo = JsonPath.read(pay.getBody(), "$.data.payNo");
        assertEquals(0, code(post("/api/v1/payments/mock/" + payNo + "/confirm", Map.of(), token)));
        assertEquals("CONFIRMED", jdbcTemplate.queryForObject(
                "SELECT status FROM booking WHERE id = ?", String.class, bookingId));

        String checkinToken = JsonPath.read(
                get("/api/v1/bookings/" + bookingId + "/checkin-token", token).getBody(), "$.data.token");
        // 挪进核销窗口
        jdbcTemplate.update("UPDATE booking_item SET start_at = DATE_ADD(NOW(), INTERVAL 10 MINUTE), "
                + "end_at = DATE_ADD(NOW(), INTERVAL 70 MINUTE) WHERE booking_id = ?", bookingId);
        assertEquals(0, code(post("/api/v1/admin/bookings/" + bookingId + "/checkin",
                Map.of("token", checkinToken), adminLogin())));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT checked_in_at IS NOT NULL FROM booking WHERE id = ?", Boolean.class, bookingId));
    }

    // ---- helpers ----

    private String adminLogin() {
        ResponseEntity<String> login = post("/api/v1/auth/login",
                Map.of("grantType", "PASSWORD", "phone", "13800000000", "password", "admin123"), null);
        assertEquals(0, code(login));
        return JsonPath.read(login.getBody(), "$.data.accessToken");
    }

    private ResponseEntity<String> hold(String token, long slotId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", "q-" + UUID.randomUUID());
        return restTemplate.exchange("/api/v1/bookings/holds", HttpMethod.POST,
                new HttpEntity<>(Map.of("slotIds", List.of(slotId)), headers), String.class);
    }

    private String registerAndLogin() {
        String phone = "150" + String.format("%08d", Math.abs(System.nanoTime()) % 100_000_000);
        assertEquals(0, code(post("/api/v1/auth/sms-code", Map.of("phone", phone, "scene", "REGISTER"), null)));
        ResponseEntity<String> register = post("/api/v1/auth/register",
                Map.of("phone", phone, "code", "123456"), null);
        assertEquals(0, code(register));
        return JsonPath.read(register.getBody(), "$.data.accessToken");
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
