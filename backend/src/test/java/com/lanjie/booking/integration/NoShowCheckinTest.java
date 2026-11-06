package com.lanjie.booking.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jayway.jsonpath.JsonPath;
import com.lanjie.booking.entity.Slot;
import com.lanjie.booking.mapper.SlotMapper;
import com.lanjie.booking.scheduler.NoShowScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W4 集成测试：核销码（一次性 + 时间窗）与爽约封禁（3 次/7 天 → 禁订 7 天） */
class NoShowCheckinTest extends IntegrationTestBase {

    @Autowired
    private SlotMapper slotMapper;
    @Autowired
    private NoShowScheduler noShowScheduler;

    @Test
    void checkinTokenIsOneTimeAndWindowed() {
        String token = registerAndLogin();
        freeUp(1, LocalDate.now().plusDays(3));
        Slot slot = slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                .eq(Slot::getCourtId, 1)
                .eq(Slot::getSlotDate, LocalDate.now().plusDays(3))
                .eq(Slot::getStatus, com.lanjie.booking.enums.SlotStatus.AVAILABLE)
                .gt(Slot::getStartAt, LocalDateTime.now())
                .orderByAsc(Slot::getStartAt)
                .last("LIMIT 1")).get(0);
        long bookingId = holdAndPay(token, List.of(slot.getId()));

        ResponseEntity<String> tokenResp = get("/api/v1/bookings/" + bookingId + "/checkin-token", token);
        assertEquals(0, code(tokenResp));
        String checkinToken = JsonPath.read(tokenResp.getBody(), "$.data.token");

        // 把场次挪进核销窗口（开场后 10 分钟）
        jdbcTemplate.update(
                "UPDATE booking_item SET start_at = DATE_ADD(NOW(), INTERVAL 10 MINUTE), "
                        + "end_at = DATE_ADD(NOW(), INTERVAL 70 MINUTE) WHERE booking_id = ?",
                bookingId);

        String admin = adminLogin();
        assertEquals(0, code(post("/api/v1/admin/bookings/" + bookingId + "/checkin",
                Map.of("token", checkinToken), admin)));
        assertEquals("CONFIRMED", bookingStatus(bookingId));

        // 一次性：核销后原码失效
        assertEquals(40407, code(post("/api/v1/admin/bookings/" + bookingId + "/checkin",
                Map.of("token", checkinToken), admin)));
    }

    @Test
    void checkinWindowIsEnforced() {
        String token = registerAndLogin();
        // day+3 的场次：现在必然不在核销窗口内
        freeUp(2, LocalDate.now().plusDays(3));
        Slot slot = slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                .eq(Slot::getCourtId, 2)
                .eq(Slot::getSlotDate, LocalDate.now().plusDays(3))
                .eq(Slot::getStatus, com.lanjie.booking.enums.SlotStatus.AVAILABLE)
                .gt(Slot::getStartAt, LocalDateTime.now())
                .orderByAsc(Slot::getStartAt)
                .last("LIMIT 1")).get(0);
        long bookingId = holdAndPay(token, List.of(slot.getId()));
        String checkinToken = JsonPath.read(
                get("/api/v1/bookings/" + bookingId + "/checkin-token", token).getBody(), "$.data.token");
        String admin = adminLogin();
        assertEquals(40408, code(post("/api/v1/admin/bookings/" + bookingId + "/checkin",
                Map.of("token", checkinToken), admin)));
    }

    @Test
    void threeNoShowsIn7DaysTriggerBan() {
        String token = registerAndLogin();
        for (int i = 1; i <= 3; i++) {
            freeUp(1, LocalDate.now().plusDays(i + 3));
            Slot slot = slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                    .eq(Slot::getCourtId, 1)
                    .eq(Slot::getSlotDate, LocalDate.now().plusDays(i + 3))
                    .eq(Slot::getStatus, com.lanjie.booking.enums.SlotStatus.AVAILABLE)
                    .gt(Slot::getStartAt, LocalDateTime.now())
                    .orderByAsc(Slot::getStartAt)
                    .last("LIMIT 1")).get(0);
            long bookingId = holdAndPay(token, List.of(slot.getId()));
            // 把场次时间挪到过去，触发爽约判定
            jdbcTemplate.update(
                    "UPDATE booking_item SET start_at = DATE_SUB(NOW(), INTERVAL 2 HOUR), "
                            + "end_at = DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE booking_id = ?",
                    bookingId);
            noShowScheduler.sweep();
            assertEquals("NO_SHOW", bookingStatus(bookingId));
        }
        // 第 4 次下单：被封禁拒绝
        freeUp(1, LocalDate.now().plusDays(7));
        Slot another = slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                .eq(Slot::getCourtId, 1)
                .eq(Slot::getSlotDate, LocalDate.now().plusDays(7))
                .eq(Slot::getStatus, com.lanjie.booking.enums.SlotStatus.AVAILABLE)
                .gt(Slot::getStartAt, LocalDateTime.now())
                .orderByAsc(Slot::getStartAt)
                .last("LIMIT 1")).get(0);
        assertEquals(40109, code(hold(token, List.of(another.getId()))));
    }

    /** 测试自清理：历史运行残留可能占着目标场×日的格子，先强制释放 */
    private void freeUp(int courtId, java.time.LocalDate date) {
        jdbcTemplate.update("UPDATE slot SET status = 'AVAILABLE', hold_token = NULL, hold_expires_at = NULL, "
                + "owner_type = NULL, owner_id = NULL WHERE court_id = ? AND slot_date = ? "
                + "AND status IN ('HELD', 'BOOKED')", courtId, date);
    }

    // ---- helpers ----

    private Slot firstAvailableSlot(int courtId, int offset) {
        return slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                .eq(Slot::getCourtId, courtId)
                .eq(Slot::getSlotDate, LocalDate.now().plusDays(1))
                .eq(Slot::getStatus, com.lanjie.booking.enums.SlotStatus.AVAILABLE)
                .gt(Slot::getStartAt, LocalDateTime.now())
                .orderByAsc(Slot::getStartAt)
                .last("LIMIT 1 OFFSET " + (offset - 1))).get(0);
    }

    private String bookingStatus(long bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, bookingId);
    }

    private String registerAndLogin() {
        String phone = "134" + String.format("%08d", Math.abs(System.nanoTime()) % 100_000_000);
        assertEquals(0, code(post("/api/v1/auth/sms-code", Map.of("phone", phone, "scene", "REGISTER"), null)));
        ResponseEntity<String> register = post("/api/v1/auth/register",
                Map.of("phone", phone, "code", "123456"), null);
        assertEquals(0, code(register));
        return JsonPath.read(register.getBody(), "$.data.accessToken");
    }

    private String adminLogin() {
        ResponseEntity<String> login = post("/api/v1/auth/login",
                Map.of("grantType", "PASSWORD", "phone", "13800000000", "password", "admin123"), null);
        assertEquals(0, code(login));
        return JsonPath.read(login.getBody(), "$.data.accessToken");
    }

    private long holdAndPay(String token, List<Long> slotIds) {
        ResponseEntity<String> holdResponse = hold(token, slotIds);
        assertEquals(0, code(holdResponse));
        long bookingId = ((Number) JsonPath.read(holdResponse.getBody(), "$.data.bookingId")).longValue();
        ResponseEntity<String> pay = post("/api/v1/bookings/" + bookingId + "/pay",
                Map.of("channel", "WECHAT_MOCK"), token);
        assertEquals(0, code(pay));
        String payNo = JsonPath.read(pay.getBody(), "$.data.payNo");
        assertEquals(0, code(post("/api/v1/payments/mock/" + payNo + "/confirm", Map.of(), token)));
        return bookingId;
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
        assertTrue(response.getStatusCode().is2xxSuccessful(), () -> "http " + response.getStatusCode());
        return (int) JsonPath.read(response.getBody(), "$.code");
    }
}
