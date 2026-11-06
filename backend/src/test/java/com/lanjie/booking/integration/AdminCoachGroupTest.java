package com.lanjie.booking.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 教练与团课：入驻、排班、请假冲突全退，团课开班/名单/取消整班。
 */
class AdminCoachGroupTest extends IntegrationTestBase {

    @Test
    void onboardCoachBuildScheduleAndDisable() {
        String admin = adminLogin();
        String phone = "135" + String.format("%08d", Math.abs(System.nanoTime()) % 100_000_000);

        ResponseEntity<String> created = post("/api/v1/admin/coaches", Map.of(
                "phone", phone,
                "password", "coach888",
                "displayName", "测试教练",
                "specialties", "投篮专项",
                "basePriceCents", 20000,
                "commissionRate", 0.2
        ), admin);
        assertEquals(0, code(created));
        long coachId = readLong(created.getBody(), "$.data");

        // 新教练能用自己的账号登录
        ResponseEntity<String> login = post("/api/v1/auth/login", Map.of(
                "grantType", "PASSWORD", "phone", phone, "password", "coach888"), null);
        assertEquals(0, code(login));
        String coachToken = JsonPath.read(login.getBody(), "$.data.accessToken");
        assertEquals(0, code(get("/api/v1/coach/me/schedule", coachToken)));

        // 改成每周一天 10:00–12:00，未来档期只剩这一天两格
        LocalDate targetDay = LocalDate.now().plusDays(2);
        int weekday = targetDay.getDayOfWeek().getValue();
        ResponseEntity<String> scheduled = exchange("/api/v1/admin/coaches/" + coachId + "/schedule",
                HttpMethod.PUT, Map.of("rules", List.of(
                        Map.of("weekday", weekday, "startTime", "10:00", "endTime", "12:00"))), admin);
        assertEquals(0, code(scheduled));

        ResponseEntity<String> schedule = get("/api/v1/admin/coaches/" + coachId + "/schedule", admin);
        assertEquals(0, code(schedule));
        assertEquals(1, (int) JsonPath.read(schedule.getBody(), "$.data.rules.length()"));
        List<String> starts = JsonPath.read(schedule.getBody(), "$.data.upcoming[*].startAt");
        assertEquals(2, starts.size());
        assertTrue(starts.stream().allMatch(s -> s.startsWith(targetDay.toString())
                && (s.contains("T10:") || s.contains("T11:"))));

        // 停用后没有可约档期，列表价格同步
        ResponseEntity<String> updated = exchange("/api/v1/admin/coaches/" + coachId, HttpMethod.PUT,
                Map.of("basePriceCents", 25000, "status", "DISABLED"), admin);
        assertEquals(0, code(updated));
        ResponseEntity<String> after = get("/api/v1/admin/coaches/" + coachId + "/schedule", admin);
        List<String> statuses = JsonPath.read(after.getBody(), "$.data.upcoming[*].status");
        assertEquals(0, statuses.size());
        List<Integer> prices = JsonPath.read(get("/api/v1/admin/coaches", admin).getBody(),
                "$.data[?(@.id == " + coachId + ")].basePriceCents");
        assertEquals(25000, prices.get(0));
    }

    @Test
    void timeOffListsSoldLessonsThenRefundsAfterConfirm() {
        String admin = adminLogin();
        String customer = registerAndLogin();
        LocalDateTime start = LocalDate.now().plusDays(2).atTime(11, 0);

        jdbcTemplate.update("UPDATE coach_slot SET status = 'AVAILABLE', hold_token = NULL, "
                + "hold_expires_at = NULL, owner_type = NULL, owner_id = NULL "
                + "WHERE coach_id = 1 AND start_at = ?", start);
        jdbcTemplate.update("UPDATE slot SET status = 'AVAILABLE', hold_token = NULL, "
                + "hold_expires_at = NULL, owner_type = NULL, owner_id = NULL WHERE start_at = ?", start);
        long coachSlotId = jdbcTemplate.queryForObject(
                "SELECT id FROM coach_slot WHERE coach_id = 1 AND start_at = ?", Long.class, start);
        long courtSlotId = jdbcTemplate.queryForObject(
                "SELECT id FROM slot WHERE start_at = ? AND status = 'AVAILABLE' ORDER BY id LIMIT 1",
                Long.class, start);
        long serviceId = jdbcTemplate.queryForObject(
                "SELECT id FROM coach_service WHERE coach_id = 1 AND type = 'PRIVATE' LIMIT 1", Long.class);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(customer);
        headers.set("Idempotency-Key", "coach-hold-" + UUID.randomUUID());
        ResponseEntity<String> hold = restTemplate.exchange("/api/v1/coach-bookings/holds", HttpMethod.POST,
                new HttpEntity<>(Map.of("coachSlotId", coachSlotId, "serviceId", serviceId,
                        "courtSlotId", courtSlotId), headers), String.class);
        assertEquals(0, code(hold));
        long bookingId = readLong(hold.getBody(), "$.data.bookingId");
        ResponseEntity<String> pay = post("/api/v1/bookings/" + bookingId + "/pay",
                Map.of("channel", "WECHAT_MOCK"), customer);
        assertEquals(0, code(pay));
        String payNo = JsonPath.read(pay.getBody(), "$.data.payNo");
        assertEquals(0, code(post("/api/v1/payments/mock/" + payNo + "/confirm", Map.of(), customer)));
        long paid = jdbcTemplate.queryForObject(
                "SELECT paid_cents FROM booking WHERE id = ?", Long.class, bookingId);

        // 不带 force：只提示，不动订单
        ResponseEntity<String> preview = post("/api/v1/admin/coaches/1/time-off",
                Map.of("startAt", start.toString(), "endAt", start.plusHours(1).toString(),
                        "reason", "外出培训", "force", false), admin);
        assertEquals(0, code(preview));
        assertEquals(true, JsonPath.read(preview.getBody(), "$.data.blocked"));
        assertEquals(1, (int) JsonPath.read(preview.getBody(), "$.data.affected.length()"));
        assertEquals("CONFIRMED", bookingStatus(bookingId));

        // 确认后：订单取消、全退、教练与场地档期释放
        ResponseEntity<String> forced = post("/api/v1/admin/coaches/1/time-off",
                Map.of("startAt", start.toString(), "endAt", start.plusHours(1).toString(),
                        "reason", "外出培训", "force", true), admin);
        assertEquals(0, code(forced));
        assertEquals(1, (int) JsonPath.read(forced.getBody(), "$.data.cancelled.length()"));
        long timeOffId = readLong(forced.getBody(), "$.data.timeOffId");
        assertEquals("CANCELLED", bookingStatus(bookingId));
        assertEquals(paid, refundAmount(bookingId));
        // 请假区间整体关闭，教练档期最终是 CLOSED；场地释放
        assertEquals("CLOSED", jdbcTemplate.queryForObject(
                "SELECT status FROM coach_slot WHERE id = ?", String.class, coachSlotId));
        assertEquals("AVAILABLE", jdbcTemplate.queryForObject(
                "SELECT status FROM slot WHERE id = ?", String.class, courtSlotId));

        // 撤销请假：时段重新开放
        assertEquals(0, code(exchange("/api/v1/admin/coaches/1/time-offs/" + timeOffId,
                HttpMethod.DELETE, null, admin)));
        assertEquals("AVAILABLE", jdbcTemplate.queryForObject(
                "SELECT status FROM coach_slot WHERE id = ?", String.class, coachSlotId));
    }

    @Test
    void groupClassRosterAndCancelRefundsEveryone() {
        String admin = adminLogin();
        String customer = registerAndLogin();
        LocalDate day = LocalDate.now().plusDays(2);
        LocalDateTime start = day.atTime(14, 0);

        jdbcTemplate.update("UPDATE coach_slot SET status = 'AVAILABLE', hold_token = NULL, "
                + "hold_expires_at = NULL, owner_type = NULL, owner_id = NULL "
                + "WHERE coach_id = 3 AND start_at = ?", start);
        jdbcTemplate.update("UPDATE slot SET status = 'AVAILABLE', hold_token = NULL, "
                + "hold_expires_at = NULL, owner_type = NULL, owner_id = NULL "
                + "WHERE court_id = 2 AND start_at = ?", start);
        long serviceId = jdbcTemplate.queryForObject(
                "SELECT id FROM coach_service WHERE coach_id = 3 AND type = 'GROUP' LIMIT 1", Long.class);

        ResponseEntity<String> created = post("/api/v1/admin/group-classes",
                Map.of("coachId", 3, "serviceId", serviceId, "startAt", start.toString(), "courtId", 2), admin);
        assertEquals(0, code(created));
        long classId = readLong(created.getBody(), "$.data.id");

        ResponseEntity<String> enrolled = post("/api/v1/group-classes/" + classId + "/enroll", Map.of(), customer);
        assertEquals(0, code(enrolled));
        long bookingId = readLong(enrolled.getBody(), "$.data.bookingId");
        ResponseEntity<String> pay = post("/api/v1/bookings/" + bookingId + "/pay",
                Map.of("channel", "WECHAT_MOCK"), customer);
        assertEquals(0, code(pay));
        String payNo = JsonPath.read(pay.getBody(), "$.data.payNo");
        assertEquals(0, code(post("/api/v1/payments/mock/" + payNo + "/confirm", Map.of(), customer)));

        ResponseEntity<String> detail = get("/api/v1/admin/group-classes/" + classId, admin);
        assertEquals(0, code(detail));
        assertEquals(1, (int) JsonPath.read(detail.getBody(), "$.data.roster.length()"));
        assertEquals("CONFIRMED", JsonPath.read(detail.getBody(), "$.data.roster[0].status"));

        ResponseEntity<String> list = get("/api/v1/admin/group-classes?date=" + day, admin);
        assertEquals(0, code(list));
        assertTrue(list.getBody().contains(String.valueOf(classId)));

        ResponseEntity<String> cancelled = post("/api/v1/admin/group-classes/" + classId + "/cancel",
                Map.of("reason", "场地临时检修"), admin);
        assertEquals(0, code(cancelled));
        assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM group_class WHERE id = ?", String.class, classId));
        assertEquals("CANCELLED", bookingStatus(bookingId));
        assertEquals(8000, refundAmount(bookingId));
        assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM group_enrollment WHERE class_id = ? LIMIT 1", String.class, classId));
        assertEquals("AVAILABLE", jdbcTemplate.queryForObject(
                "SELECT cs.status FROM coach_slot cs JOIN group_class g ON g.coach_slot_id = cs.id "
                        + "WHERE g.id = ?", String.class, classId));
        assertEquals("AVAILABLE", jdbcTemplate.queryForObject(
                "SELECT s.status FROM slot s JOIN group_class g ON g.court_slot_id = s.id "
                        + "WHERE g.id = ?", String.class, classId));

        // 再取消一次被拒绝
        assertNotEquals(0, code(post("/api/v1/admin/group-classes/" + classId + "/cancel",
                Map.of("reason", "重复操作"), admin)));
    }

    // ---- helpers ----

    private String bookingStatus(long bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, bookingId);
    }

    private long refundAmount(long bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents), 0) FROM refund_order WHERE booking_id = ? AND status = 'SUCCESS'",
                Long.class, bookingId);
    }

    private String adminLogin() {
        ResponseEntity<String> response = post("/api/v1/auth/login",
                Map.of("grantType", "PASSWORD", "phone", "13800000000", "password", "admin123"), null);
        assertEquals(0, code(response));
        return JsonPath.read(response.getBody(), "$.data.accessToken");
    }

    private String registerAndLogin() {
        String phone = "134" + String.format("%08d", Math.abs(System.nanoTime()) % 100_000_000);
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
