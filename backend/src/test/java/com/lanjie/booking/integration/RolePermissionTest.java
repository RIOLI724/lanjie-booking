package com.lanjie.booking.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
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

/**
 * P2 角色边界：前台能做日常接待与排期，退款/改价/财务/审计必须店长。
 */
class RolePermissionTest extends IntegrationTestBase {

    @Test
    void frontDeskCanRunDailyWorkButNotMoneyActions() {
        String front = login("13800000001", "front123");
        String manager = login("13800000000", "admin123");

        ResponseEntity<String> me = get("/api/v1/me", front);
        assertEquals(0, code(me));
        assertEquals("FRONT_DESK", JsonPath.read(me.getBody(), "$.data.role"));

        // 日常页面/查询：允许
        assertEquals(0, code(get("/api/v1/admin/today", front)));
        assertEquals(0, code(get("/api/v1/admin/schedule", front)));
        assertEquals(0, code(get("/api/v1/admin/orders", front)));
        assertEquals(0, code(get("/api/v1/admin/coaches", front)));
        assertEquals(0, code(get("/api/v1/admin/coaches/1/schedule", front)));
        assertEquals(0, code(get("/api/v1/admin/group-classes?date=" + LocalDate.now(), front)));
        assertEquals(0, code(get("/api/v1/admin/refunds", front)));

        // 店长专属：拒绝
        assertForbidden(get("/api/v1/admin/finance/summary?days=7", front));
        assertForbidden(get("/api/v1/admin/audit-logs", front));
        assertForbidden(get("/api/v1/admin/users", front));
        assertForbidden(get("/api/v1/admin/ai/summary", front));
        assertForbidden(exchange("/api/v1/admin/slots/1/price", HttpMethod.PATCH,
                Map.of("priceCents", 10000), front));
        assertForbidden(post("/api/v1/admin/orders/1/force-cancel",
                Map.of("reason", "越权测试"), front));
        assertForbidden(post("/api/v1/admin/coaches", Map.of(
                "phone", "13100000000", "password", "abcdef", "displayName", "越权",
                "basePriceCents", 10000), front));
        assertForbidden(post("/api/v1/admin/group-classes/1/cancel",
                Map.of("reason", "越权测试"), front));

        // 店长仍可做
        assertEquals(0, code(get("/api/v1/admin/finance/summary?days=7", manager)));
        long slotId = jdbcTemplate.queryForObject(
                "SELECT id FROM slot WHERE slot_date = ? AND status = 'AVAILABLE' ORDER BY id LIMIT 1",
                Long.class, LocalDate.now().plusDays(2));
        assertEquals(0, code(exchange("/api/v1/admin/slots/" + slotId + "/price",
                HttpMethod.PATCH, Map.of("priceCents", 12345), manager)));
        assertEquals(12345, jdbcTemplate.queryForObject(
                "SELECT price_cents FROM slot WHERE id = ?", Integer.class, slotId));
    }

    @Test
    void frontDeskCanReachCheckinEndpoint() {
        String front = login("13800000001", "front123");
        ResponseEntity<String> response = post("/api/v1/admin/bookings/999999/checkin",
                Map.of("token", "123456"), front);
        // 不应被权限拦成 403；订单不存在走业务错误
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void frontDeskCannotForceCloseBookedSlot() {
        String front = login("13800000001", "front123");
        String manager = login("13800000000", "admin123");
        String customer = registerAndLogin();
        long slotId = jdbcTemplate.queryForObject(
                "SELECT id FROM slot WHERE slot_date = ? AND status = 'AVAILABLE' ORDER BY id LIMIT 1",
                Long.class, LocalDate.now().plusDays(2));
        long bookingId = holdAndPay(customer, slotId);

        // 普通关场请求只返回受影响清单
        ResponseEntity<String> preview = post("/api/v1/admin/slots/" + slotId + "/close",
                Map.of("force", false, "reason", "前台尝试关场"), front);
        assertEquals(0, code(preview));
        assertEquals(true, JsonPath.read(preview.getBody(), "$.data.blocked"));

        // 强制退款关场：前台被业务层拦下（40301），订单不动
        ResponseEntity<String> denied = post("/api/v1/admin/slots/" + slotId + "/close",
                Map.of("force", true, "reason", "前台尝试强制关场"), front);
        assertEquals(40301, code(denied));
        assertEquals("CONFIRMED", jdbcTemplate.queryForObject(
                "SELECT status FROM booking WHERE id = ?", String.class, bookingId));

        // 店长可以
        assertEquals(0, code(post("/api/v1/admin/slots/" + slotId + "/close",
                Map.of("force", true, "reason", "店长确认关场"), manager)));
        assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM booking WHERE id = ?", String.class, bookingId));
    }

    // ---- helpers ----

    private void assertForbidden(ResponseEntity<String> response) {
        assertEquals(403, response.getStatusCode().value());
    }

    private long holdAndPay(String token, long slotId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", "hold-" + UUID.randomUUID());
        ResponseEntity<String> hold = restTemplate.exchange("/api/v1/bookings/holds", HttpMethod.POST,
                new HttpEntity<>(Map.of("slotIds", List.of(slotId)), headers), String.class);
        assertEquals(0, code(hold));
        long bookingId = readLong(hold.getBody(), "$.data.bookingId");
        ResponseEntity<String> pay = post("/api/v1/bookings/" + bookingId + "/pay",
                Map.of("channel", "WECHAT_MOCK"), token);
        assertEquals(0, code(pay));
        String payNo = JsonPath.read(pay.getBody(), "$.data.payNo");
        assertEquals(0, code(post("/api/v1/payments/mock/" + payNo + "/confirm", Map.of(), token)));
        return bookingId;
    }

    private String login(String phone, String password) {
        ResponseEntity<String> response = post("/api/v1/auth/login",
                Map.of("grantType", "PASSWORD", "phone", phone, "password", password), null);
        assertEquals(0, code(response));
        return JsonPath.read(response.getBody(), "$.data.accessToken");
    }

    private String registerAndLogin() {
        String phone = "131" + String.format("%08d", Math.abs(System.nanoTime()) % 100_000_000);
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
        if (!response.getStatusCode().is2xxSuccessful()) {
            return -1;
        }
        return (int) JsonPath.read(response.getBody(), "$.code");
    }

    private long readLong(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }
}
