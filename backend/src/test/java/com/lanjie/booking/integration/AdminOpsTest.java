package com.lanjie.booking.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jayway.jsonpath.JsonPath;
import com.lanjie.booking.entity.Slot;
import com.lanjie.booking.enums.SlotStatus;
import com.lanjie.booking.mapper.SlotMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前台 P0 运营能力：排期关场/开放/改价，订单详情与强制取消全退。
 * 重点验证「影响面先告知、确认后才退款」以及审计留痕。
 */
class AdminOpsTest extends IntegrationTestBase {

    @Autowired
    private SlotMapper slotMapper;

    @Test
    void slotCanBeClosedReopenedAndRepriced() {
        String admin = adminLogin();
        Slot slot = pickSlot(4, 1);

        ResponseEntity<String> priced = exchange("/api/v1/admin/slots/" + slot.getId() + "/price",
                HttpMethod.PATCH, Map.of("priceCents", 19900), admin);
        assertEquals(0, code(priced));
        assertEquals(19900, slotPrice(slot.getId()));

        ResponseEntity<String> closed = post("/api/v1/admin/slots/" + slot.getId() + "/close",
                Map.of("force", false, "reason", "地板维护"), admin);
        assertEquals(0, code(closed));
        assertEquals("CLOSED", slotStatus(slot.getId()));

        // 关场期间顾客订不了
        String customer = registerAndLogin();
        ResponseEntity<String> denied = hold(customer, List.of(slot.getId()));
        assertNotEquals(0, code(denied));

        assertEquals(0, code(post("/api/v1/admin/slots/" + slot.getId() + "/reopen", Map.of(), admin)));
        assertEquals("AVAILABLE", slotStatus(slot.getId()));
        assertEquals(0, code(hold(customer, List.of(slot.getId()))));

        // 已售格子不能改价
        Slot booked = pickSlot(3, 1);
        long bookingId = holdAndPay(customer, List.of(booked.getId()));
        ResponseEntity<String> reject = exchange("/api/v1/admin/slots/" + booked.getId() + "/price",
                HttpMethod.PATCH, Map.of("priceCents", 99900), admin);
        assertNotEquals(0, code(reject));
        assertEquals("BOOKED", slotStatus(booked.getId()));
        assertEquals("CONFIRMED", bookingStatus(bookingId));
    }

    @Test
    void closingBookedSlotAsksForConfirmationThenRefundsInFull() {
        String admin = adminLogin();
        String customer = registerAndLogin();
        Slot slot = pickSlot(2, 1);
        long bookingId = holdAndPay(customer, List.of(slot.getId()));
        long paid = slotPrice(slot.getId());

        // 第一次不带 force：只返回受影响订单，不动钱和状态
        ResponseEntity<String> blocked = post("/api/v1/admin/slots/" + slot.getId() + "/close",
                Map.of("force", false, "reason", "临时闭馆"), admin);
        assertEquals(0, code(blocked));
        assertEquals(true, JsonPath.read(blocked.getBody(), "$.data.blocked"));
        assertEquals(1, (int) JsonPath.read(blocked.getBody(), "$.data.affected.length()"));
        assertEquals("CONFIRMED", bookingStatus(bookingId));
        assertEquals(0, refundAmount(bookingId));

        // 确认后强制关场：订单取消、全额退款、格子关闭
        ResponseEntity<String> forced = post("/api/v1/admin/slots/" + slot.getId() + "/close",
                Map.of("force", true, "reason", "临时闭馆"), admin);
        assertEquals(0, code(forced));
        assertEquals("CLOSED", slotStatus(slot.getId()));
        assertEquals("CANCELLED", bookingStatus(bookingId));
        assertEquals(paid, refundAmount(bookingId));
        assertEquals("ADMIN", jdbcTemplate.queryForObject(
                "SELECT actor FROM booking_status_history WHERE booking_id = ? "
                        + "AND to_status = 'CANCELLED' ORDER BY id DESC LIMIT 1", String.class, bookingId));

        // 关掉的格子可以重新开放
        assertEquals(0, code(post("/api/v1/admin/slots/" + slot.getId() + "/reopen", Map.of(), admin)));
        assertEquals("AVAILABLE", slotStatus(slot.getId()));
    }

    @Test
    void orderDetailShowsTraceAndForceCancelRefundsOnce() {
        String admin = adminLogin();
        String customer = registerAndLogin();
        Slot slot = pickSlot(1, 1);
        long bookingId = holdAndPay(customer, List.of(slot.getId()));
        long paid = slotPrice(slot.getId());

        ResponseEntity<String> orders = get("/api/v1/admin/orders?date=" + LocalDate.now().plusDays(2), admin);
        assertEquals(0, code(orders));
        assertTrue(orders.getBody().contains(String.valueOf(bookingId)));

        ResponseEntity<String> detail = get("/api/v1/admin/orders/" + bookingId, admin);
        assertEquals(0, code(detail));
        assertEquals(customerName(bookingId), JsonPath.read(detail.getBody(), "$.data.userName"));
        assertTrue((int) JsonPath.read(detail.getBody(), "$.data.history.length()") >= 1);
        assertEquals(1, (int) JsonPath.read(detail.getBody(), "$.data.pays.length()"));

        ResponseEntity<String> cancel = post("/api/v1/admin/orders/" + bookingId + "/force-cancel",
                Map.of("reason", "场馆停电"), admin);
        assertEquals(0, code(cancel));
        assertEquals(paid, readLong(cancel.getBody(), "$.data.refundCents"));
        assertEquals("CANCELLED", bookingStatus(bookingId));
        assertEquals("AVAILABLE", slotStatus(slot.getId()));
        assertEquals(paid, refundAmount(bookingId));

        // 再点一次：状态已变，不会二次退款
        ResponseEntity<String> again = post("/api/v1/admin/orders/" + bookingId + "/force-cancel",
                Map.of("reason", "场馆停电"), admin);
        assertNotEquals(0, code(again));
        assertEquals(paid, refundAmount(bookingId));
    }

    @Test
    void forceCancelRestoresCouponAndPoints() {
        String admin = adminLogin();
        String customer = registerAndLogin();
        Slot slot = pickSlot(4, 2);
        long bookingId = holdAndPay(customer, List.of(slot.getId()));
        long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM booking WHERE id = ?", Long.class, bookingId);

        long couponId = jdbcTemplate.queryForObject(
                "SELECT id FROM coupon_template ORDER BY id LIMIT 1", Long.class);
        jdbcTemplate.update("INSERT INTO user_coupon (user_id, template_id, status, used_at, booking_id, expires_at) "
                + "VALUES (?, ?, 'USED', NOW(), ?, DATE_ADD(NOW(), INTERVAL 30 DAY))",
                userId, couponId, bookingId);
        long userCouponId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_coupon WHERE user_id = ? ORDER BY id DESC LIMIT 1", Long.class, userId);
        jdbcTemplate.update("UPDATE booking SET coupon_id = ?, points_used = 100 WHERE id = ?",
                userCouponId, bookingId);
        jdbcTemplate.update("INSERT INTO points_account (user_id, balance, total_earned, total_spent, version) "
                + "VALUES (?, 0, 0, 100, 0)", userId);

        assertEquals(0, code(post("/api/v1/admin/orders/" + bookingId + "/force-cancel",
                Map.of("reason", "场馆原因，全额退"), admin)));
        assertEquals("UNUSED", jdbcTemplate.queryForObject(
                "SELECT status FROM user_coupon WHERE id = ?", String.class, userCouponId));
        assertEquals(100, jdbcTemplate.queryForObject(
                "SELECT balance FROM points_account WHERE user_id = ?", Integer.class, userId));
    }

    @Test
    void customerCannotUseAdminOps() {
        String customer = registerAndLogin();
        ResponseEntity<String> response = get("/api/v1/admin/orders", customer);
        assertEquals(403, response.getStatusCode().value());
    }

    // ---- helpers ----

    private Slot pickSlot(int courtId, int offset) {
        java.time.LocalDate day = LocalDate.now().plusDays(2);
        jdbcTemplate.update("UPDATE slot SET status = 'AVAILABLE', hold_token = NULL, hold_expires_at = NULL, "
                + "owner_type = NULL, owner_id = NULL WHERE court_id = ? AND slot_date = ? "
                + "AND status IN ('HELD', 'BOOKED')", courtId, day);
        return slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                .eq(Slot::getCourtId, courtId)
                .eq(Slot::getSlotDate, day)
                .eq(Slot::getStatus, SlotStatus.AVAILABLE)
                .gt(Slot::getStartAt, LocalDateTime.now())
                .orderByAsc(Slot::getStartAt)
                .last("LIMIT 1 OFFSET " + (offset - 1))).get(0);
    }

    private String customerName(long bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT u.nickname FROM user u JOIN booking b ON b.user_id = u.id WHERE b.id = ?",
                String.class, bookingId);
    }

    private long slotPrice(long slotId) {
        return jdbcTemplate.queryForObject("SELECT price_cents FROM slot WHERE id = ?", Long.class, slotId);
    }

    private String slotStatus(long slotId) {
        return jdbcTemplate.queryForObject("SELECT status FROM slot WHERE id = ?", String.class, slotId);
    }

    private String bookingStatus(long bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, bookingId);
    }

    private long refundAmount(long bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents), 0) FROM refund_order WHERE booking_id = ? AND status = 'SUCCESS'",
                Long.class, bookingId);
    }

    private long holdAndPay(String token, List<Long> slotIds) {
        ResponseEntity<String> hold = hold(token, slotIds);
        assertEquals(0, code(hold));
        long bookingId = readLong(hold.getBody(), "$.data.bookingId");
        ResponseEntity<String> pay = post("/api/v1/bookings/" + bookingId + "/pay",
                Map.of("channel", "WECHAT_MOCK"), token);
        assertEquals(0, code(pay));
        String payNo = JsonPath.read(pay.getBody(), "$.data.payNo");
        assertEquals(0, code(post("/api/v1/payments/mock/" + payNo + "/confirm", Map.of(), token)));
        return bookingId;
    }

    private String adminLogin() {
        ResponseEntity<String> response = post("/api/v1/auth/login",
                Map.of("grantType", "PASSWORD", "phone", "13800000000", "password", "admin123"), null);
        assertEquals(0, code(response));
        return JsonPath.read(response.getBody(), "$.data.accessToken");
    }

    private String registerAndLogin() {
        String phone = "137" + String.format("%08d", Math.abs(System.nanoTime()) % 100_000_000);
        assertEquals(0, code(post("/api/v1/auth/sms-code", Map.of("phone", phone, "scene", "REGISTER"), null)));
        ResponseEntity<String> register = post("/api/v1/auth/register",
                Map.of("phone", phone, "code", "123456"), null);
        assertEquals(0, code(register));
        return JsonPath.read(register.getBody(), "$.data.accessToken");
    }

    private ResponseEntity<String> hold(String token, List<Long> slotIds) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", "hold-" + UUID.randomUUID());
        return restTemplate.exchange("/api/v1/bookings/holds", HttpMethod.POST,
                new HttpEntity<>(Map.of("slotIds", slotIds), headers), String.class);
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
        if (!response.getStatusCode().is2xxSuccessful()) {
            return -1;
        }
        return (int) JsonPath.read(response.getBody(), "$.code");
    }

    private long readLong(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }
}
