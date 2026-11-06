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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 拼场异常：自动流局、店长手动流局、发起人缺席、单个份子钱退款。
 */
class PickupExceptionTest extends IntegrationTestBase {

    @Autowired
    private PickupService pickupService;

    @Test
    void adminAbortRefundsInitiatorAndParticipants() {
        String admin = adminLogin();
        String initiator = registerAndLogin();
        String participant = registerAndLogin();
        LocalDate day = LocalDate.now().plusDays(2);
        long slotId = availableSlot(day, 0);
        long gameId = createGame(initiator, slotId);
        long shareBooking = joinGame(participant, gameId);

        ResponseEntity<String> detail = get("/api/v1/admin/pickups/" + gameId, admin);
        assertEquals(0, code(detail));
        assertEquals(2, (int) JsonPath.read(detail.getBody(), "$.data.roster.length()"));

        ResponseEntity<String> aborted = post("/api/v1/admin/pickups/" + gameId + "/abort",
                Map.of("reason", "场地临时被占用"), admin);
        assertEquals(0, code(aborted));
        assertEquals(2, (int) JsonPath.read(aborted.getBody(), "$.data"));
        assertEquals("CANCELLED", gameStatus(gameId));
        assertEquals("AVAILABLE", jdbcTemplate.queryForObject(
                "SELECT status FROM slot WHERE id = ?", String.class, slotId));
        assertEquals("CANCELLED", bookingStatus(bookingIdOfGame(gameId)));
        assertEquals("CANCELLED", bookingStatus(shareBooking));
        assertTrue(refundAmount(shareBooking) > 0);
        assertTrue(actions(admin).contains("PICKUP_ABORT"));
    }

    @Test
    void insufficientPlayersAutoAbortBeforeStart() {
        String initiator = registerAndLogin();
        LocalDate day = LocalDate.now().plusDays(2);
        long slotId = availableSlot(day, 1);
        long gameId = createGame(initiator, slotId);
        long initiatorBooking = bookingIdOfGame(gameId);

        // 把场次挪到 1 小时后，仍未满员 → 结算任务应自动流局
        LocalDateTime start = LocalDateTime.now().plusHours(1);
        jdbcTemplate.update("UPDATE slot SET start_at = ?, end_at = ? WHERE id = ?",
                start, start.plusHours(1), slotId);
        jdbcTemplate.update("UPDATE booking_item SET start_at = ?, end_at = ? WHERE booking_id = ?",
                start, start.plusHours(1), initiatorBooking);
        pickupService.settle();

        assertEquals("CANCELLED", gameStatus(gameId));
        assertEquals("CANCELLED", bookingStatus(initiatorBooking));
        assertTrue(refundAmount(initiatorBooking) > 0);
        assertEquals("AVAILABLE", jdbcTemplate.queryForObject(
                "SELECT status FROM slot WHERE id = ?", String.class, slotId));
        assertEquals("SYSTEM", jdbcTemplate.queryForObject(
                "SELECT actor FROM booking_status_history WHERE booking_id = ? "
                        + "AND to_status = 'CANCELLED' ORDER BY id DESC LIMIT 1",
                String.class, initiatorBooking));
    }

    @Test
    void initiatorNoShowRefundsSharesOnlyAndKeepsCourtFee() {
        String admin = adminLogin();
        String initiator = registerAndLogin();
        String participant = registerAndLogin();
        LocalDate day = LocalDate.now().plusDays(2);
        long slotId = availableSlot(day, 2);
        long gameId = createGame(initiator, slotId);
        long shareBooking = joinGame(participant, gameId);

        ResponseEntity<String> result = post("/api/v1/admin/pickups/" + gameId + "/initiator-no-show",
                Map.of("reason", "发起人未到场"), admin);
        assertEquals(0, code(result));
        long initiatorBooking = bookingIdOfGame(gameId);
        assertEquals("NO_SHOW", bookingStatus(initiatorBooking));
        assertEquals(0, refundAmount(initiatorBooking));
        assertEquals("CANCELLED", bookingStatus(shareBooking));
        assertTrue(refundAmount(shareBooking) > 0);
        assertEquals("COMPLETED", gameStatus(gameId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT settled FROM pickup_game WHERE id = ?", Integer.class, gameId));
        assertEquals("NO_SHOW", jdbcTemplate.queryForObject(
                "SELECT status FROM pickup_participant WHERE game_id = ? AND user_id = "
                        + "(SELECT initiator_id FROM pickup_game WHERE id = ?) LIMIT 1",
                String.class, gameId, gameId));
        assertTrue(actions(admin).contains("PICKUP_INITIATOR_NO_SHOW"));
    }

    @Test
    void adminCanRefundSingleParticipantShare() {
        String admin = adminLogin();
        String initiator = registerAndLogin();
        String participant = registerAndLogin();
        LocalDate day = LocalDate.now().plusDays(2);
        long slotId = availableSlot(day, 3);
        long gameId = createGame(initiator, slotId);
        long shareBooking = joinGame(participant, gameId);
        long participantId = jdbcTemplate.queryForObject(
                "SELECT id FROM pickup_participant WHERE game_id = ? AND booking_id = ?",
                Long.class, gameId, shareBooking);

        ResponseEntity<String> refunded = post(
                "/api/v1/admin/pickups/" + gameId + "/participants/" + participantId + "/refund",
                Map.of("reason", "参与者临时受伤"), admin);
        assertEquals(0, code(refunded));
        assertEquals("CANCELLED", bookingStatus(shareBooking));
        assertTrue(refundAmount(shareBooking) > 0);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT joined_players FROM pickup_game WHERE id = ?", Integer.class, gameId));
        assertEquals("OPEN", gameStatus(gameId));
    }

    // ---- helpers ----

    private long availableSlot(LocalDate day, int offset) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM slot WHERE slot_date = ? AND status = 'AVAILABLE' "
                        + "ORDER BY start_at LIMIT 1 OFFSET " + offset, Long.class, day);
    }

    private long createGame(String token, long slotId) {
        ResponseEntity<String> hold = postWithIdem("/api/v1/pickup-games",
                Map.of("slotId", slotId, "format", "3v3"), token);
        assertEquals(0, code(hold));
        long bookingId = readLong(hold.getBody(), "$.data.bookingId");
        pay(token, bookingId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM pickup_game WHERE booking_id = ?", Long.class, bookingId);
    }

    private long joinGame(String token, long gameId) {
        ResponseEntity<String> hold = postWithIdem("/api/v1/pickup-games/" + gameId + "/join",
                Map.of(), token);
        assertEquals(0, code(hold));
        long bookingId = readLong(hold.getBody(), "$.data.bookingId");
        pay(token, bookingId);
        return bookingId;
    }

    private void pay(String token, long bookingId) {
        ResponseEntity<String> pay = post("/api/v1/bookings/" + bookingId + "/pay",
                Map.of("channel", "WECHAT_MOCK"), token);
        assertEquals(0, code(pay));
        String payNo = JsonPath.read(pay.getBody(), "$.data.payNo");
        assertEquals(0, code(post("/api/v1/payments/mock/" + payNo + "/confirm", Map.of(), token)));
    }

    private long bookingIdOfGame(long gameId) {
        return jdbcTemplate.queryForObject(
                "SELECT booking_id FROM pickup_game WHERE id = ?", Long.class, gameId);
    }

    private String gameStatus(long gameId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM pickup_game WHERE id = ?", String.class, gameId);
    }

    private String bookingStatus(long bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM booking WHERE id = ?", String.class, bookingId);
    }

    private long refundAmount(long bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents), 0) FROM refund_order WHERE booking_id = ? AND status = 'SUCCESS'",
                Long.class, bookingId);
    }

    private List<String> actions(String admin) {
        return JsonPath.read(
                get("/api/v1/admin/audit-logs?date=" + LocalDate.now(), admin).getBody(),
                "$.data[*].action");
    }

    private String adminLogin() {
        ResponseEntity<String> response = post("/api/v1/auth/login",
                Map.of("grantType", "PASSWORD", "phone", "13800000000", "password", "admin123"), null);
        assertEquals(0, code(response));
        return JsonPath.read(response.getBody(), "$.data.accessToken");
    }

    private String registerAndLogin() {
        String phone = "159" + String.format("%08d", Math.abs(System.nanoTime()) % 100_000_000);
        assertEquals(0, code(post("/api/v1/auth/sms-code", Map.of("phone", phone, "scene", "REGISTER"), null)));
        ResponseEntity<String> register = post("/api/v1/auth/register",
                Map.of("phone", phone, "code", "123456"), null);
        assertEquals(0, code(register));
        return JsonPath.read(register.getBody(), "$.data.accessToken");
    }

    private ResponseEntity<String> postWithIdem(String path, Map<String, ?> body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", "pickup-" + UUID.randomUUID());
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
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
