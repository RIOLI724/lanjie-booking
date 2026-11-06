package com.lanjie.booking.integration;

import com.jayway.jsonpath.JsonPath;
import com.lanjie.booking.service.SlotGenerationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W2 集成测试：格子生成幂等、价格快照正确、订场表接口可用。
 * 依赖本机 MySQL/Redis（CI 由服务容器提供）；演示验证码 123456。
 */
class SlotModuleTest extends IntegrationTestBase {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    @Autowired
    private SlotGenerationService generationService;

    @Test
    void generationIsIdempotentAndPricesAreSnapshotted() {
        generationService.generateRange(LocalDate.now(), LocalDate.now().plusDays(29));
        generationService.generateRange(LocalDate.now(), LocalDate.now().plusDays(29));

        // 4 片场 × 每天 09:00-22:00 共 13 格 × 30 天
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM slot", Integer.class);
        assertEquals(4 * 13 * 30, total);

        // 平日晚场 19:00 → ¥70
        LocalDate weekday = LocalDate.now();
        while (weekday.getDayOfWeek() == DayOfWeek.SATURDAY || weekday.getDayOfWeek() == DayOfWeek.SUNDAY) {
            weekday = weekday.plusDays(1);
        }
        Integer weekdayPeak = jdbcTemplate.queryForObject(
                "SELECT price_cents FROM slot WHERE slot_date = ? AND HOUR(start_at) = 19 LIMIT 1",
                Integer.class, weekday);
        assertEquals(7000, weekdayPeak);

        // 周六下午 15:00 → ¥80
        LocalDate saturday = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
        Integer weekendPrice = jdbcTemplate.queryForObject(
                "SELECT price_cents FROM slot WHERE slot_date = ? AND HOUR(start_at) = 15 LIMIT 1",
                Integer.class, saturday);
        assertEquals(8000, weekendPrice);
    }

    @Test
    void bookingGridApiWorks() {
        String token = registerAndLogin();

        ResponseEntity<String> slots = get("/api/v1/slots?date=" + LocalDate.now().format(ISO), token);
        assertEquals(0, code(slots));
        int count = (int) JsonPath.read(slots.getBody(), "$.data.length()");
        assertEquals(4 * 13, count);
        int firstPrice = (int) JsonPath.read(slots.getBody(), "$.data[0].priceCents");
        assertTrue(firstPrice > 0);

        ResponseEntity<String> calendar = get("/api/v1/slots/calendar", token);
        assertEquals(0, code(calendar));
        int days = (int) JsonPath.read(calendar.getBody(), "$.data.length()");
        assertEquals(7, days);
        assertEquals("09:00", JsonPath.read(calendar.getBody(), "$.data[0].openTime"));

        ResponseEntity<String> courts = get("/api/v1/courts", token);
        assertEquals(0, code(courts));
        assertEquals("1 号场", JsonPath.read(courts.getBody(), "$.data[0].name"));

        ResponseEntity<String> venue = get("/api/v1/venue", token);
        assertEquals(0, code(venue));
        assertEquals("城东体育馆", JsonPath.read(venue.getBody(), "$.data.name"));

        // 非法日期 → 参数错误
        ResponseEntity<String> badDate = get("/api/v1/slots?date=not-a-date", token);
        assertEquals(40000, code(badDate));
    }

    private String registerAndLogin() {
        String phone = "137" + String.format("%08d", Math.abs(System.nanoTime()) % 100_000_000);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> send = post("/api/v1/auth/sms-code",
                Map.of("phone", phone, "scene", "REGISTER"), null);
        assertEquals(0, code(send));
        ResponseEntity<String> register = post("/api/v1/auth/register",
                Map.of("phone", phone, "code", "123456"), null);
        assertEquals(0, code(register));
        return JsonPath.read(register.getBody(), "$.data.accessToken");
    }

    private int code(ResponseEntity<String> response) {
        return (int) JsonPath.read(response.getBody(), "$.code");
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
}
