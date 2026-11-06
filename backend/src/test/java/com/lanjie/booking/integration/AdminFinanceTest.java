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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 财务：营收/退款净额、流水、CSV 导出、调余额。
 */
class AdminFinanceTest extends IntegrationTestBase {

    @Test
    void revenueRefundFlowAndCsvExport() {
        String admin = adminLogin();
        String customer = registerAndLogin();
        LocalDate day = LocalDate.now().plusDays(2);
        long slotId = jdbcTemplate.queryForObject(
                "SELECT id FROM slot WHERE slot_date = ? AND status = 'AVAILABLE' ORDER BY id LIMIT 1",
                Long.class, day);
        long bookingId = holdAndPay(customer, slotId);
        long paid = jdbcTemplate.queryForObject(
                "SELECT paid_cents FROM booking WHERE id = ?", Long.class, bookingId);
        String payNo = jdbcTemplate.queryForObject(
                "SELECT pay_no FROM pay_order WHERE booking_id = ? AND status = 'PAID'", String.class, bookingId);

        ResponseEntity<String> summary = get("/api/v1/admin/finance/summary?days=7", admin);
        assertEquals(0, code(summary));
        assertEquals(paid, readLong(summary.getBody(), "$.data.revenueCents"));
        assertEquals(0, readLong(summary.getBody(), "$.data.refundCents"));
        assertEquals(paid, readLong(summary.getBody(), "$.data.netCents"));
        assertEquals(1, (int) JsonPath.read(summary.getBody(), "$.data.orderCount"));
        List<String> types = JsonPath.read(summary.getBody(), "$.data.byType[*].type");
        assertTrue(types.contains("COURT"));

        // 场馆原因全退：收入仍在、退款单列、净额归零
        assertEquals(0, code(post("/api/v1/admin/orders/" + bookingId + "/force-cancel",
                Map.of("reason", "临时闭馆"), admin)));
        ResponseEntity<String> after = get("/api/v1/admin/finance/summary?days=7", admin);
        assertEquals(paid, readLong(after.getBody(), "$.data.revenueCents"));
        assertEquals(paid, readLong(after.getBody(), "$.data.refundCents"));
        assertEquals(0, readLong(after.getBody(), "$.data.netCents"));

        ResponseEntity<String> txns = get("/api/v1/admin/finance/transactions?from=" + LocalDate.now()
                + "&to=" + LocalDate.now(), admin);
        assertEquals(0, code(txns));
        List<String> refs = JsonPath.read(txns.getBody(), "$.data[*].ref");
        assertTrue(refs.contains(payNo));
        List<String> directions = JsonPath.read(txns.getBody(), "$.data[*].direction");
        assertTrue(directions.contains("IN"));
        assertTrue(directions.contains("OUT"));

        ResponseEntity<String> csv = get("/api/v1/admin/finance/export?from=" + LocalDate.now()
                + "&to=" + LocalDate.now(), admin);
        assertTrue(csv.getStatusCode().is2xxSuccessful());
        assertTrue(csv.getBody() != null && csv.getBody().contains("场地"));
        assertTrue(csv.getBody().contains(payNo));
    }

    @Test
    void walletAdjustWritesTxnAndAudit() {
        String admin = adminLogin();
        String customer = registerAndLogin();
        long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM user WHERE phone = ?", Long.class, lastPhone);

        ResponseEntity<String> credit = post("/api/v1/admin/customers/" + userId + "/wallet-adjust",
                Map.of("amountCents", 10000, "reason", "投诉补偿"), admin);
        assertEquals(0, code(credit));
        assertEquals(10000, readLong(credit.getBody(), "$.data"));
        assertEquals(10000, jdbcTemplate.queryForObject(
                "SELECT balance_cents FROM wallet_account WHERE user_id = ?", Long.class, userId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_txn WHERE user_id = ? AND type = 'ADJUST'", Integer.class, userId));

        ResponseEntity<String> debit = post("/api/v1/admin/customers/" + userId + "/wallet-adjust",
                Map.of("amountCents", -3000, "reason", "收回多退"), admin);
        assertEquals(0, code(debit));
        assertEquals(7000, readLong(debit.getBody(), "$.data"));

        // 余额不足：拒绝且余额不变
        assertNotEquals(0, code(post("/api/v1/admin/customers/" + userId + "/wallet-adjust",
                Map.of("amountCents", -999999, "reason", "越界扣款"), admin)));
        assertEquals(7000, jdbcTemplate.queryForObject(
                "SELECT balance_cents FROM wallet_account WHERE user_id = ?", Long.class, userId));

        List<String> actions = JsonPath.read(
                get("/api/v1/admin/audit-logs?date=" + LocalDate.now(), admin).getBody(), "$.data[*].action");
        assertTrue(actions.contains("CUSTOMER_WALLET_ADJUST"));
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
        String phone = "132" + String.format("%08d", Math.abs(System.nanoTime()) % 100_000_000);
        lastPhone = phone;
        assertEquals(0, code(post("/api/v1/auth/sms-code", Map.of("phone", phone, "scene", "REGISTER"), null)));
        ResponseEntity<String> register = post("/api/v1/auth/register",
                Map.of("phone", phone, "code", "123456"), null);
        assertEquals(0, code(register));
        return JsonPath.read(register.getBody(), "$.data.accessToken");
    }

    private String lastPhone;

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
