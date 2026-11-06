package com.lanjie.booking.integration;

import com.jayway.jsonpath.JsonPath;
import com.lanjie.booking.service.PickupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W7 集成测试：拼场发起/加入/退出/取消退款/托管结算 */
class PickupTest extends IntegrationTestBase {

    @Autowired
    private PickupService pickupService;

    @Test
    void createJoinQuitCancelAndSettle() {
        String initiator = registerAndLogin();
        String joiner = registerAndLogin();
        String joiner2 = registerAndLogin();

        // 发起（3v3，day+3 14:00 场地3）→ 付全场 → OPEN
        long slotId = slotId(3, 3, "14:00");
        ResponseEntity<String> created = post("/api/v1/pickup-games",
                Map.of("slotId", slotId, "format", "3v3", "note", "来两个会打的"), initiator);
        assertEquals(0, code(created));
        long bookingId = readLong(created.getBody(), "$.data.bookingId");
        long gameId = latestGameId();
        assertEquals(0, code(payAndConfirm(initiator, bookingId)));
        assertEquals("OPEN", gameStatus(gameId));
        assertEquals(1, joined(gameId));

        // 加入 → 付份子钱 → 2 人
        ResponseEntity<String> join = post("/api/v1/pickup-games/" + gameId + "/join", Map.of(), joiner);
        assertEquals(0, code(join));
        assertEquals(0, code(payAndConfirm(joiner, readLong(join.getBody(), "$.data.bookingId"))));
        assertEquals(2, joined(gameId));
        int share = readInt(join.getBody(), "$.data.payableCents");
        assertTrue(share > 0);

        // 列表可见
        ResponseEntity<String> list = get("/api/v1/pickup-games", joiner2);
        assertEquals(0, code(list));
        assertTrue(Integer.parseInt(JsonPath.read(list.getBody(), "$.data.length()").toString()) >= 1);

        // join2 加入后退场（≥24h 全退）→ 人数回落
        ResponseEntity<String> join2 = post("/api/v1/pickup-games/" + gameId + "/join", Map.of(), joiner2);
        assertEquals(0, code(join2));
        assertEquals(0, code(payAndConfirm(joiner2, readLong(join2.getBody(), "$.data.bookingId"))));
        assertEquals(3, joined(gameId));
        assertEquals(0, code(delete("/api/v1/pickup-games/" + gameId + "/join", joiner2)));
        assertEquals(2, joined(gameId));

        // 发起人取消 → 场次取消，参与者份子钱全退
        assertEquals(0, code(post("/api/v1/bookings/" + bookingId + "/cancel", Map.of(), initiator)));
        assertEquals("CANCELLED", gameStatus(gameId));
    }

    @Test
    void escrowSettlesToInitiatorAfterStart() {
        String initiator = registerAndLogin();
        String joiner = registerAndLogin();

        long slotId = slotId(4, 3, "15:00");
        long bookingId = readLong(post("/api/v1/pickup-games",
                Map.of("slotId", slotId, "format", "4v4"), initiator).getBody(), "$.data.bookingId");
        long gameId = latestGameId();
        assertEquals(0, code(payAndConfirm(initiator, bookingId)));

        ResponseEntity<String> join = post("/api/v1/pickup-games/" + gameId + "/join", Map.of(), joiner);
        long share = readLong(join.getBody(), "$.data.payableCents");
        assertEquals(0, code(payAndConfirm(joiner, readLong(join.getBody(), "$.data.bookingId"))));

        // 把开赛时间挪到过去 2 小时，触发结算
        jdbcTemplate.update("UPDATE slot SET start_at = DATE_SUB(NOW(), INTERVAL 2 HOUR), "
                + "end_at = DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE id = ?", slotId);
        long before = balance(initiator);
        pickupService.settle();
        long after = balance(initiator);
        assertEquals(before + share, after);
        Integer settled = jdbcTemplate.queryForObject(
                "SELECT settled FROM pickup_game WHERE id = ?", Integer.class, gameId);
        assertEquals(1, settled);
    }

    // ---- helpers ----

    private long slotId(int courtId, int dayOffset, String time) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM slot WHERE court_id = ? AND slot_date = DATE_ADD(CURDATE(), INTERVAL ? DAY) "
                        + "AND HOUR(start_at) = ? AND status = 'AVAILABLE' LIMIT 1",
                Long.class, courtId, dayOffset, Integer.parseInt(time.substring(0, 2)));
    }

    private long latestGameId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM pickup_game ORDER BY id DESC LIMIT 1", Long.class);
    }

    private String gameStatus(long gameId) {
        return jdbcTemplate.queryForObject("SELECT status FROM pickup_game WHERE id = ?", String.class, gameId);
    }

    private int joined(long gameId) {
        return jdbcTemplate.queryForObject(
                "SELECT joined_players FROM pickup_game WHERE id = ?", Integer.class, gameId);
    }

    private long balance(String token) {
        return readLong(get("/api/v1/wallet", token).getBody(), "$.data.balanceCents");
    }

    private ResponseEntity<String> payAndConfirm(String token, long bookingId) {
        ResponseEntity<String> pay = post("/api/v1/bookings/" + bookingId + "/pay",
                Map.of("channel", "WECHAT_MOCK"), token);
        if (code(pay) != 0) {
            return pay;
        }
        String payNo = JsonPath.read(pay.getBody(), "$.data.payNo");
        return post("/api/v1/payments/mock/" + payNo + "/confirm", Map.of(), token);
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

    private ResponseEntity<String> delete(String path, String token) {
        return exchange(path, HttpMethod.DELETE, null, token);
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

    private int readInt(String json, String path) {
        return ((Number) JsonPath.read(json, path)).intValue();
    }
}
