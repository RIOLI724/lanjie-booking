package com.lanjie.booking.integration;

import com.jayway.jsonpath.JsonPath;
import com.lanjie.booking.scheduler.NoShowScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W8 集成测试：会员升级、折扣与券择优、积分抵扣、全退返券返积分、完成发积分 */
class MembershipPricingTest extends IntegrationTestBase {

    @Autowired
    private NoShowScheduler noShowScheduler;

    @Test
    void memberUpgradeAndCouponVersusMemberDiscount() {
        String token = registerAndLogin();
        long userId = readLong(get("/api/v1/me", token).getBody(), "$.data.id");
        assertEquals("普通", JsonPath.read(get("/api/v1/me/member", token).getBody(), "$.data.levelName"));

        // 充值 1000+150 → 银卡 98 折
        long planId = readLong(get("/api/v1/recharge-plans", token).getBody(), "$.data[1].id");
        String rechargeNo = JsonPath.read(post("/api/v1/wallet/recharge",
                Map.of("planId", planId, "channel", "WECHAT_MOCK"), token).getBody(), "$.data.payNo");
        assertEquals(0, code(confirm(rechargeNo, token)));
        assertEquals("银卡", JsonPath.read(get("/api/v1/me/member", token).getBody(), "$.data.levelName"));

        // 管理员发新人场地券（满 100 减 20）
        long templateId = jdbcTemplate.queryForObject(
                "SELECT id FROM coupon_template WHERE name = '新人场地券' LIMIT 1", Long.class);
        assertEquals(0, code(post("/api/v1/admin/coupons/grant",
                Map.of("userId", userId, "templateId", templateId), adminLogin())));
        long couponId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_coupon WHERE user_id = ? ORDER BY id DESC LIMIT 1", Long.class, userId);

        // 80 元订单：券不达门槛 → 会员 98 折生效（7840）
        long bookingId = holdAndPay(token, List.of(slotId(1, 3, 16), slotId(1, 3, 17)), "WECHAT_MOCK",
                Map.of("channel", "WECHAT_MOCK", "couponId", couponId));
        assertEquals(7840, readLong(get("/api/v1/bookings/" + bookingId, token).getBody(), "$.data.paidCents"));

        // ≥24h 取消 → 全退（7840）且券未用掉
        assertEquals(0, code(post("/api/v1/bookings/" + bookingId + "/cancel", Map.of(), token)));
        assertEquals("UNUSED", jdbcTemplate.queryForObject(
                "SELECT status FROM user_coupon WHERE id = ?", String.class, couponId));

        // 120 元订单：券减 20 更优（会员只减 2.4）→ 券生效，再用 2000 积分抵 20 → 应付 80 元
        jdbcTemplate.update("INSERT INTO points_account(user_id, balance, total_earned, total_spent, version) "
                + "VALUES (?, 5000, 5000, 0, 0) ON DUPLICATE KEY UPDATE balance = 5000", userId);
        long booking2 = holdAndPay(token, List.of(slotId(1, 3, 18), slotId(1, 3, 19)), "WECHAT_MOCK",
                Map.of("channel", "WECHAT_MOCK", "couponId", couponId, "pointsToUse", 2000));
        var detail = get("/api/v1/bookings/" + booking2, token);
        assertEquals(10000, readLong(detail.getBody(), "$.data.paidCents")); // 12000-2000
        assertEquals(3000, jdbcTemplate.queryForObject(
                "SELECT balance FROM points_account WHERE user_id = ?", Integer.class, userId));
        assertEquals("USED", jdbcTemplate.queryForObject(
                "SELECT status FROM user_coupon WHERE id = ?", String.class, couponId));

        // 全退 → 券恢复、积分退回
        assertEquals(0, code(post("/api/v1/bookings/" + booking2 + "/cancel", Map.of(), token)));
        assertEquals("UNUSED", jdbcTemplate.queryForObject(
                "SELECT status FROM user_coupon WHERE id = ?", String.class, couponId));
        assertEquals(5000, jdbcTemplate.queryForObject(
                "SELECT balance FROM points_account WHERE user_id = ?", Integer.class, userId));
    }

    @Test
    void completedBookingEarnsPoints() {
        String token = registerAndLogin();
        long userId = readLong(get("/api/v1/me", token).getBody(), "$.data.id");
        long bookingId = holdAndPay(token, List.of(slotId(2, 3, 15)), "WECHAT_MOCK",
                Map.of("channel", "WECHAT_MOCK"));
        // 模拟已核销且场次结束 → 调度转完成并发放积分（4000 分实付 → 40 分 × 1.0）
        jdbcTemplate.update("UPDATE booking SET checked_in_at = NOW() WHERE id = ?", bookingId);
        jdbcTemplate.update("UPDATE booking_item SET start_at = DATE_SUB(NOW(), INTERVAL 2 HOUR), "
                + "end_at = DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE booking_id = ?", bookingId);
        noShowScheduler.sweep();
        assertEquals("COMPLETED", jdbcTemplate.queryForObject(
                "SELECT status FROM booking WHERE id = ?", String.class, bookingId));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(points),0) FROM points_transaction WHERE user_id = ? AND type = 'EARN'",
                Integer.class, userId) > 0);
    }

    // ---- helpers ----

    private long slotId(int courtId, int dayOffset, int hour) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM slot WHERE court_id = ? AND slot_date = DATE_ADD(CURDATE(), INTERVAL ? DAY) "
                        + "AND HOUR(start_at) = ? AND status = 'AVAILABLE' LIMIT 1",
                Long.class, courtId, dayOffset, hour);
    }

    private long holdAndPay(String token, List<Long> slotIds, String channel, Map<String, Object> payBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", "m-" + UUID.randomUUID());
        ResponseEntity<String> hold = restTemplate.exchange("/api/v1/bookings/holds", HttpMethod.POST,
                new HttpEntity<>(Map.of("slotIds", slotIds), headers), String.class);
        assertEquals(0, code(hold));
        long bookingId = readLong(hold.getBody(), "$.data.bookingId");
        HttpHeaders payHeaders = new HttpHeaders();
        payHeaders.setContentType(MediaType.APPLICATION_JSON);
        payHeaders.setBearerAuth(token);
        payHeaders.set("Idempotency-Key", "p-" + UUID.randomUUID());
        ResponseEntity<String> pay = restTemplate.exchange("/api/v1/bookings/" + bookingId + "/pay",
                HttpMethod.POST, new HttpEntity<>(payBody, payHeaders), String.class);
        assertEquals(0, code(pay));
        String payNo = JsonPath.read(pay.getBody(), "$.data.payNo");
        assertEquals(0, code(confirm(payNo, token)));
        return bookingId;
    }

    private String adminLogin() {
        ResponseEntity<String> login = post("/api/v1/auth/login",
                Map.of("grantType", "PASSWORD", "phone", "13800000000", "password", "admin123"), null);
        assertEquals(0, code(login));
        return JsonPath.read(login.getBody(), "$.data.accessToken");
    }

    private String registerAndLogin() {
        String phone = "130" + String.format("%08d", Math.abs(System.nanoTime()) % 100_000_000);
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
