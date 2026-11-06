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

/** P2 时段热力图：4 片场 × 9–21 点，已售格子计入占用。 */
class AdminAnalyticsTest extends IntegrationTestBase {

    @Test
    void heatmapAggregatesBookedSlots() {
        String admin = adminLogin();
        String customer = registerAndLogin();
        LocalDate day = LocalDate.now().plusDays(2);
        long slotId = jdbcTemplate.queryForObject(
                "SELECT id FROM slot WHERE slot_date = ? AND status = 'AVAILABLE' ORDER BY id LIMIT 1",
                Long.class, day);
        int hour = jdbcTemplate.queryForObject(
                "SELECT HOUR(start_at) FROM slot WHERE id = ?", Integer.class, slotId);
        holdAndPay(customer, slotId);

        ResponseEntity<String> heatmap = get("/api/v1/admin/analytics/heatmap?days=14", admin);
        assertEquals(0, code(heatmap));
        assertEquals(4, (int) JsonPath.read(heatmap.getBody(), "$.data.courts.length()"));
        assertEquals(13, (int) JsonPath.read(heatmap.getBody(), "$.data.courts[0].cells.length()"));
        assertEquals(9, (int) JsonPath.read(heatmap.getBody(), "$.data.courts[0].cells[0].hour"));

        List<Integer> booked = JsonPath.read(heatmap.getBody(),
                "$.data.courts[*].cells[?(@.hour == " + hour + ")].booked");
        assertTrue(booked.stream().anyMatch(value -> value >= 1));
        assertTrue((int) JsonPath.read(heatmap.getBody(), "$.data.hottest.length()") >= 1);

        assertEquals(403, get("/api/v1/admin/analytics/heatmap", customer).getStatusCode().value());
    }

    // ---- helpers ----

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

    private String adminLogin() {
        ResponseEntity<String> response = post("/api/v1/auth/login",
                Map.of("grantType", "PASSWORD", "phone", "13800000000", "password", "admin123"), null);
        assertEquals(0, code(response));
        return JsonPath.read(response.getBody(), "$.data.accessToken");
    }

    private String registerAndLogin() {
        String phone = "130" + String.format("%08d", Math.abs(System.nanoTime()) % 100_000_000);
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
