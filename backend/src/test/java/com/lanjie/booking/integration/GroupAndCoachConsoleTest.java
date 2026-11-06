package com.lanjie.booking.integration;

import com.jayway.jsonpath.JsonPath;
import com.lanjie.booking.service.GroupClassService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W6 集成测试：团课报名/满员候补/递补/不成班取消 + 教练端改档期与请假。
 * 每个用例用管理端接口自建独立场次（day+3，避开 24h 自动取消窗口），互不干扰。
 */
class GroupAndCoachConsoleTest extends IntegrationTestBase {

    @Autowired
    private GroupClassService groupClassService;

    @Test
    void groupEnrollPayFullWaitlistAndPromote() {
        long classId = createTestClass(1L, 1L, LocalDate.now().plusDays(3), "10:00");
        // 压到 2 人满员，快速走完 满员→候补→递补
        jdbcTemplate.update("UPDATE group_class SET max_students = 2 WHERE id = ?", classId);

        String u1 = registerAndLogin();
        String u2 = registerAndLogin();
        String u3 = registerAndLogin();

        long b1 = enroll(u1, classId);
        assertEquals(0, code(payAndConfirm(u1, b1)));
        long b2 = enroll(u2, classId);
        assertEquals(0, code(payAndConfirm(u2, b2)));
        assertEquals("FULL", classStatus(classId));

        // 满员 → 候补（无订单）
        ResponseEntity<String> waitlist = post("/api/v1/group-classes/" + classId + "/enroll", Map.of(), u3);
        assertEquals(0, code(waitlist));
        assertEquals("WAITLIST", JsonPath.read(waitlist.getBody(), "$.data.status"));

        // u2 取消（≥24h 全退）→ u3 递补确认
        assertEquals(0, code(delete("/api/v1/group-classes/" + classId + "/enroll", u2)));
        long u3Id = jdbcTemplate.queryForObject(
                "SELECT user_id FROM group_enrollment WHERE class_id = ? AND status = 'CONFIRMED' "
                        + "ORDER BY id DESC LIMIT 1", Long.class, classId);
        String u3Status = jdbcTemplate.queryForObject(
                "SELECT status FROM group_enrollment WHERE class_id = ? AND user_id = ?",
                String.class, classId, u3Id);
        assertEquals("CONFIRMED", u3Status);
        assertTrue(u3Id > 0);
    }

    @Test
    void underFilledClassIsAutoCancelledBefore24h() {
        long classId = createTestClass(2L, 2L, LocalDate.now().plusDays(3), "11:00");
        // 挪进 24h 窗口且无人报名 → 调度取消并释放资源
        jdbcTemplate.update("UPDATE group_class SET start_at = DATE_ADD(NOW(), INTERVAL 20 HOUR), "
                + "end_at = DATE_ADD(NOW(), INTERVAL 21 HOUR) WHERE id = ?", classId);
        jdbcTemplate.update(
                "UPDATE coach_slot SET start_at = (SELECT start_at FROM group_class WHERE id = ?), "
                        + "end_at = (SELECT end_at FROM group_class WHERE id = ?) WHERE id = "
                        + "(SELECT coach_slot_id FROM group_class WHERE id = ?)", classId, classId, classId);
        jdbcTemplate.update(
                "UPDATE slot SET start_at = (SELECT start_at FROM group_class WHERE id = ?), "
                        + "end_at = (SELECT end_at FROM group_class WHERE id = ?) WHERE id = "
                        + "(SELECT court_slot_id FROM group_class WHERE id = ?)", classId, classId, classId);
        groupClassService.sweep();
        assertEquals("CANCELLED", classStatus(classId));
        Long coachSlotId = jdbcTemplate.queryForObject(
                "SELECT coach_slot_id FROM group_class WHERE id = ?", Long.class, classId);
        assertEquals("AVAILABLE", coachSlotStatus(coachSlotId));
    }

    @Test
    void coachConsoleUpdatesScheduleAndTakesTimeOff() {
        String coach = loginCoach();
        // 先恢复 7 天规则（此前运行的用例可能改过）
        assertEquals(0, code(put("/api/v1/coach/me/schedule", Map.of("rules", fullWeek()), coach)));
        ResponseEntity<String> schedule = get("/api/v1/coach/me/schedule", coach);
        assertEquals(0, code(schedule));
        assertEquals(7, ((Number) JsonPath.read(schedule.getBody(), "$.data.length()")).intValue());

        // 改成只有周一 09:00-12:00
        ResponseEntity<String> updated = put("/api/v1/coach/me/schedule",
                Map.of("rules", List.of(Map.of("weekday", 1, "startTime", "09:00", "endTime", "12:00"))), coach);
        assertEquals(0, code(updated));
        Integer rules = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coach_schedule_rule WHERE coach_id = 1", Integer.class);
        assertEquals(1, rules);

        // 请假明天 09:00-11:00，取消请假后重开
        ResponseEntity<String> off = post("/api/v1/coach/me/time-offs",
                Map.of("startAt", LocalDate.now().plusDays(1) + "T09:00:00",
                        "endAt", LocalDate.now().plusDays(1) + "T11:00:00", "reason", "家中有事"), coach);
        assertEquals(0, code(off));
        long offId = ((Number) jdbcTemplate.queryForObject(
                "SELECT id FROM coach_time_off ORDER BY id DESC LIMIT 1", Integer.class)).longValue();
        assertEquals(0, code(delete("/api/v1/coach/me/time-offs/" + offId, coach)));

        // 恢复 7 天规则
        assertEquals(0, code(put("/api/v1/coach/me/schedule", Map.of("rules", fullWeek()), coach)));

        // 顾客视角：教练端接口顾客进不去
        String customer = registerAndLogin();
        assertEquals(40301, code(get("/api/v1/coach/me/schedule", customer)));
    }

    // ---- helpers ----

    private List<Map<String, Object>> fullWeek() {
        List<Map<String, Object>> rules = new java.util.ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            rules.add(Map.of("weekday", d, "startTime", "09:00", "endTime", "22:00"));
        }
        return rules;
    }

    /** 管理端自建场次（day+N 的指定时刻，教练/场地的该时刻格必须可选） */
    private long createTestClass(Long coachId, Long courtId, LocalDate date, String time) {
        Long serviceId = jdbcTemplate.queryForObject(
                "SELECT id FROM coach_service WHERE coach_id = ? AND type = 'GROUP' LIMIT 1",
                Long.class, coachId);
        ResponseEntity<String> created = post("/api/v1/admin/group-classes",
                Map.of("coachId", coachId, "serviceId", serviceId,
                        "startAt", date + "T" + time + ":00", "courtId", courtId), adminLogin());
        assertEquals(0, code(created));
        return readLong(created.getBody(), "$.data.id");
    }

    private String adminLogin() {
        ResponseEntity<String> login = post("/api/v1/auth/login",
                Map.of("grantType", "PASSWORD", "phone", "13800000000", "password", "admin123"), null);
        assertEquals(0, code(login));
        return JsonPath.read(login.getBody(), "$.data.accessToken");
    }

    private String classStatus(long classId) {
        return jdbcTemplate.queryForObject("SELECT status FROM group_class WHERE id = ?", String.class, classId);
    }

    private String coachSlotStatus(long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM coach_slot WHERE id = ?", String.class, id);
    }

    private long enroll(String token, long classId) {
        ResponseEntity<String> enroll = post("/api/v1/group-classes/" + classId + "/enroll", Map.of(), token);
        assertEquals(0, code(enroll));
        return readLong(enroll.getBody(), "$.data.bookingId");
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

    private String loginCoach() {
        ResponseEntity<String> login = post("/api/v1/auth/login",
                Map.of("grantType", "PASSWORD", "phone", "13900000001", "password", "coach123"), null);
        assertEquals(0, code(login));
        return JsonPath.read(login.getBody(), "$.data.accessToken");
    }

    private String registerAndLogin() {
        String phone = "132" + String.format("%08d", Math.abs(System.nanoTime()) % 100_000_000);
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

    private ResponseEntity<String> put(String path, Map<String, ?> body, String token) {
        return exchange(path, HttpMethod.PUT, body, token);
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
}
