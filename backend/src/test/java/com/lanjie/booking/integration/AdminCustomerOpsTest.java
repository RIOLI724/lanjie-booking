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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 客户运营与审计：发券、补课包、封禁/解封、操作日志可查。
 */
class AdminCustomerOpsTest extends IntegrationTestBase {

    @Test
    void grantCouponAndPackageThenSeeAuditTrail() {
        String admin = adminLogin();
        Account customer = register();
        long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM user WHERE phone = ?", Long.class, customer.phone());

        long templateId = ((Number) JsonPath.read(
                get("/api/v1/admin/coupons/templates", admin).getBody(), "$.data[0].id")).longValue();
        assertEquals(0, code(post("/api/v1/admin/coupons/grant",
                Map.of("userId", userId, "templateId", templateId), admin)));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_coupon WHERE user_id = ?", Integer.class, userId));
        ResponseEntity<String> coupons = get("/api/v1/admin/customers/" + userId + "/coupons", admin);
        assertEquals(0, code(coupons));
        assertEquals(1, (int) JsonPath.read(coupons.getBody(), "$.data.length()"));

        long serviceId = jdbcTemplate.queryForObject(
                "SELECT id FROM coach_service WHERE coach_id = 1 AND type = 'PRIVATE' LIMIT 1", Long.class);
        assertEquals(0, code(post("/api/v1/admin/customers/" + userId + "/packages",
                Map.of("coachId", 1, "serviceId", serviceId, "sessions", 5, "reason", "课程延误补偿"), admin)));
        Map<String, Object> pkg = jdbcTemplate.queryForMap(
                "SELECT total_sessions, remaining_sessions, price_cents FROM coach_package WHERE user_id = ?", userId);
        assertEquals(5, ((Number) pkg.get("total_sessions")).intValue());
        assertEquals(5, ((Number) pkg.get("remaining_sessions")).intValue());
        assertEquals(0, ((Number) pkg.get("price_cents")).intValue());

        ResponseEntity<String> customer360 = get("/api/v1/admin/customer?phone=" + customer.phone(), admin);
        assertEquals(0, code(customer360));
        assertEquals(userId, readLong(customer360.getBody(), "$.data.userId"));
        assertEquals("ACTIVE", JsonPath.read(customer360.getBody(), "$.data.status"));

        // 排期关场也应留审计
        long slotId = jdbcTemplate.queryForObject(
                "SELECT id FROM slot WHERE slot_date = ? AND status = 'AVAILABLE' ORDER BY id LIMIT 1",
                Long.class, LocalDate.now().plusDays(2));
        assertEquals(0, code(post("/api/v1/admin/slots/" + slotId + "/close",
                Map.of("force", false, "reason", "地板打蜡"), admin)));

        List<String> actions = JsonPath.read(
                get("/api/v1/admin/audit-logs?date=" + LocalDate.now(), admin).getBody(), "$.data[*].action");
        assertTrue(actions.contains("CUSTOMER_GRANT_COUPON"));
        assertTrue(actions.contains("CUSTOMER_GRANT_PACKAGE"));
        assertTrue(actions.contains("SLOT_CLOSE"));
        String actor = JsonPath.read(
                get("/api/v1/admin/audit-logs?date=" + LocalDate.now(), admin).getBody(),
                "$.data[0].actorName");
        assertEquals("前台王姐", actor);
    }

    @Test
    void banAndUnbanAreAudited() {
        String admin = adminLogin();
        Account customer = register();
        long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM user WHERE phone = ?", Long.class, customer.phone());

        assertEquals(0, code(post("/api/v1/admin/users/" + userId + "/ban",
                Map.of("unban", false, "days", 7, "reason", "多次爽约"), admin)));
        assertEquals("FROZEN", jdbcTemplate.queryForObject(
                "SELECT status FROM user WHERE id = ?", String.class, userId));
        assertEquals(0, code(post("/api/v1/admin/users/" + userId + "/ban",
                Map.of("unban", true), admin)));
        assertEquals("ACTIVE", jdbcTemplate.queryForObject(
                "SELECT status FROM user WHERE id = ?", String.class, userId));

        List<String> actions = JsonPath.read(
                get("/api/v1/admin/audit-logs?date=" + LocalDate.now(), admin).getBody(), "$.data[*].action");
        assertTrue(actions.contains("CUSTOMER_BAN"));
        assertTrue(actions.contains("CUSTOMER_UNBAN"));
    }

    // ---- helpers ----

    private record Account(String phone, String token) {
    }

    private Account register() {
        String phone = "133" + String.format("%08d", Math.abs(System.nanoTime()) % 100_000_000);
        assertEquals(0, code(post("/api/v1/auth/sms-code", Map.of("phone", phone, "scene", "REGISTER"), null)));
        ResponseEntity<String> register = post("/api/v1/auth/register",
                Map.of("phone", phone, "code", "123456"), null);
        assertEquals(0, code(register));
        return new Account(phone, JsonPath.read(register.getBody(), "$.data.accessToken"));
    }

    private String adminLogin() {
        ResponseEntity<String> response = post("/api/v1/auth/login",
                Map.of("grantType", "PASSWORD", "phone", "13800000000", "password", "admin123"), null);
        assertEquals(0, code(response));
        return JsonPath.read(response.getBody(), "$.data.accessToken");
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
