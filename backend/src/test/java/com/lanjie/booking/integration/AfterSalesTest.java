package com.lanjie.booking.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jayway.jsonpath.JsonPath;
import com.lanjie.booking.entity.Slot;
import com.lanjie.booking.mapper.SlotMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W4 集成测试：钱包（充值/余额支付/幂等）、退款阶梯、改期多退少补。
 * 阶梯比例的边界由 RefundLadderTest 钉死；这里用 jdbc 平移场次时间来固定比例档位。
 */
class AfterSalesTest extends IntegrationTestBase {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    @Autowired
    private SlotMapper slotMapper;

    @Test
    void walletRechargeAndBalancePayment() {
        String token = registerAndLogin();
        freeUp(1, LocalDate().plusDays(2));
        freeUp(2, LocalDate().plusDays(2));

        // 充值 500+50
        ResponseEntity<String> plans = get("/api/v1/recharge-plans", token);
        assertEquals(0, code(plans));
        long planId = readLong(plans.getBody(), "$.data[0].id");

        ResponseEntity<String> recharge = post("/api/v1/wallet/recharge",
                Map.of("planId", planId, "channel", "WECHAT_MOCK"), token);
        assertEquals(0, code(recharge));
        String payNo = JsonPath.read(recharge.getBody(), "$.data.payNo");
        assertEquals(0, code(confirm(payNo, token)));
        assertEquals(55000, readLong(get("/api/v1/wallet", token).getBody(), "$.data.balanceCents"));

        // 回调重放：余额不变（流水唯一键兜底）
        assertEquals(0, code(confirm(payNo, token)));
        assertEquals(55000, readLong(get("/api/v1/wallet", token).getBody(), "$.data.balanceCents"));

        // 余额支付
        Slot slot = firstAvailableSlot(1, 1);
        long bookingId = holdAndPay(token, List.of(slot.getId()), "WALLET");
        assertEquals(55000 - slotPrice(slot.getId()), balance(token));
        assertEquals("CONFIRMED", bookingStatus(bookingId));

        // 余额不足：把余额压到刚好不够这单，支付被拒且钱和状态都不动
        Slot big = firstAvailableSlot(2, 4);
        ResponseEntity<String> bigHold = hold(token, List.of(big.getId()));
        assertEquals(0, code(bigHold));
        long bigBooking = readLong(bigHold.getBody(), "$.data.bookingId");
        long price = slotPrice(big.getId());
        jdbcTemplate.update("UPDATE wallet_account SET balance_cents = ? WHERE user_id = "
                + "(SELECT user_id FROM booking WHERE id = ?)", price - 1, bigBooking);
        ResponseEntity<String> poor = post("/api/v1/bookings/" + bigBooking + "/pay",
                Map.of("channel", "WALLET"), token);
        assertEquals(40413, code(poor));
        assertEquals(price - 1, balance(token));
        assertEquals(0, code(post("/api/v1/bookings/" + bigBooking + "/cancel", Map.of(), token)));
    }

    @Test
    void cancelRefundFollowsLadder() {
        String token = registerAndLogin();
        freeUp(1, LocalDate().plusDays(2));
        freeUp(2, LocalDate().plusDays(2));
        freeUp(3, LocalDate().plusDays(2));

        // 70% 档：场次平移到现在 10 小时后
        Slot slot70 = firstAvailableSlot(1, 1);
        long booking70 = holdAndPay(token, List.of(slot70.getId()), "WECHAT_MOCK");
        shiftItemStart(booking70, 10);
        long paid70 = slotPrice(slot70.getId());
        assertEquals(0, code(post("/api/v1/bookings/" + booking70 + "/cancel", Map.of(), token)));
        assertEquals("CANCELLED", bookingStatus(booking70));
        assertEquals("AVAILABLE", slotStatus(slot70.getId()));
        assertEquals(paid70 * 70 / 100, refundAmount(booking70));

        // 100% 档：场次平移到现在 30 小时后
        Slot slot100 = firstAvailableSlot(2, 1);
        long booking100 = holdAndPay(token, List.of(slot100.getId()), "WECHAT_MOCK");
        shiftItemStart(booking100, 30);
        long paid100 = slotPrice(slot100.getId());
        assertEquals(0, code(post("/api/v1/bookings/" + booking100 + "/cancel", Map.of(), token)));
        assertEquals(paid100, refundAmount(booking100));

        // 0% 档：平移到现在 1 小时后 → 取消允许但不退
        Slot slot0 = firstAvailableSlot(3, 1);
        long booking0 = holdAndPay(token, List.of(slot0.getId()), "WECHAT_MOCK");
        shiftItemStart(booking0, 1);
        assertEquals(0, code(post("/api/v1/bookings/" + booking0 + "/cancel", Map.of(), token)));
        assertEquals(0, refundAmount(booking0));
    }

    @Test
    void rescheduleWithPriceDifference() {
        String token = registerAndLogin();
        // 自清理：历史测试残留可能占着格子，先强制释放（测试库数据归测试管）
        freeUp(4, LocalDate().plusDays(2));
        // 找一对「相邻且升价」的格子（平日 17→18 点、周末 09→10 点），低价订入、改期到高价
        List<Slot> daySlots = slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                .eq(Slot::getCourtId, 4).eq(Slot::getSlotDate, LocalDate().plusDays(2))
                .eq(Slot::getStatus, com.lanjie.booking.enums.SlotStatus.AVAILABLE)
                .orderByAsc(Slot::getStartAt));
        Slot cheap = null;
        Slot dear = null;
        for (int i = 0; i + 1 < daySlots.size(); i++) {
            if (daySlots.get(i).getStartAt().plusHours(1).equals(daySlots.get(i + 1).getStartAt())
                    && daySlots.get(i + 1).getPriceCents() > daySlots.get(i).getPriceCents()) {
                cheap = daySlots.get(i);
                dear = daySlots.get(i + 1);
                break;
            }
        }
        assertTrue(cheap != null, "测试日没有相邻升价格子");
        long expectedDiff = dear.getPriceCents() - cheap.getPriceCents();
        long bookingId = holdAndPay(token, List.of(cheap.getId()), "WECHAT_MOCK");
        shiftItemStart(bookingId, 12);

        ResponseEntity<String> reschedule = post("/api/v1/bookings/" + bookingId + "/reschedule",
                Map.of("slotIds", List.of(dear.getId())), token);
        assertEquals(0, code(reschedule));
        long diff = readLong(reschedule.getBody(), "$.data.diffCents");
        assertTrue(diff > 0);
        String payNo = JsonPath.read(reschedule.getBody(), "$.data.payNo");
        assertEquals(0, code(confirm(payNo, token)));

        assertEquals("BOOKED", slotStatus(dear.getId()));
        assertEquals("AVAILABLE", slotStatus(cheap.getId()));
        assertEquals(expectedDiff, diff);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT reschedule_count FROM booking WHERE id = ?", Integer.class, bookingId);
        assertEquals(1, count);

        // 第二次改期：拒绝
        Slot another = firstAvailableSlot(4, 1);
        assertEquals(40405, code(post("/api/v1/bookings/" + bookingId + "/reschedule",
                Map.of("slotIds", List.of(another.getId())), token)));
    }

    /** 测试自清理：历史运行残留可能占着目标场×日的格子，先强制释放 */
    private void freeUp(int courtId, java.time.LocalDate date) {
        jdbcTemplate.update("UPDATE slot SET status = 'AVAILABLE', hold_token = NULL, hold_expires_at = NULL, "
                + "owner_type = NULL, owner_id = NULL WHERE court_id = ? AND slot_date = ? "
                + "AND status IN ('HELD', 'BOOKED')", courtId, date);
    }

    // ---- helpers ----

    private java.time.LocalDate LocalDate() {
        return java.time.LocalDate.now();
    }

    private void shiftItemStart(long bookingId, long hoursAhead) {
        LocalDateTime start = LocalDateTime.now().plusHours(hoursAhead);
        jdbcTemplate.update(
                "UPDATE booking_item SET start_at = ?, end_at = ? WHERE booking_id = ?",
                start, start.plusHours(1), bookingId);
    }

    private long slotPrice(long slotId) {
        return jdbcTemplate.queryForObject("SELECT price_cents FROM slot WHERE id = ?", Long.class, slotId);
    }

    private long balance(String token) {
        return readLong(get("/api/v1/wallet", token).getBody(), "$.data.balanceCents");
    }

    private long refundAmount(long bookingId) {
        Integer amount = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents), 0) FROM refund_order WHERE booking_id = ? AND status = 'SUCCESS'",
                Integer.class, bookingId);
        return amount;
    }

    private long holdAndPay(String token, List<Long> slotIds, String channel) {
        ResponseEntity<String> holdResponse = hold(token, slotIds);
        assertEquals(0, code(holdResponse));
        long bookingId = readLong(holdResponse.getBody(), "$.data.bookingId");
        ResponseEntity<String> pay = post("/api/v1/bookings/" + bookingId + "/pay",
                Map.of("channel", channel), token);
        assertEquals(0, code(pay));
        if ("WECHAT_MOCK".equals(channel)) {
            String payNo = JsonPath.read(pay.getBody(), "$.data.payNo");
            assertEquals(0, code(confirm(payNo, token)));
        }
        return bookingId;
    }

    private Slot firstAvailableSlot(int courtId, int offset) {
        return slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                .eq(Slot::getCourtId, courtId)
                .eq(Slot::getSlotDate, LocalDate().plusDays(2))
                .eq(Slot::getStatus, com.lanjie.booking.enums.SlotStatus.AVAILABLE)
                .gt(Slot::getStartAt, LocalDateTime.now())
                .orderByAsc(Slot::getStartAt)
                .last("LIMIT 1 OFFSET " + (offset - 1))).get(0);
    }

    private String slotStatus(long slotId) {
        return jdbcTemplate.queryForObject("SELECT status FROM slot WHERE id = ?", String.class, slotId);
    }

    private String bookingStatus(long bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, bookingId);
    }

    private String registerAndLogin() {
        String phone = "136" + String.format("%08d", Math.abs(System.nanoTime()) % 100_000_000);
        assertEquals(0, code(post("/api/v1/auth/sms-code", Map.of("phone", phone, "scene", "REGISTER"), null)));
        ResponseEntity<String> register = post("/api/v1/auth/register",
                Map.of("phone", phone, "code", "123456"), null);
        assertEquals(0, code(register));
        return JsonPath.read(register.getBody(), "$.data.accessToken");
    }

    private ResponseEntity<String> hold(String token, List<Long> slotIds) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", "hold-" + UUID.randomUUID());
        return restTemplate.exchange("/api/v1/bookings/holds", HttpMethod.POST,
                new HttpEntity<>(Map.of("slotIds", slotIds), headers), String.class);
    }

    private ResponseEntity<String> confirm(String payNo, String token) {
        return post("/api/v1/payments/mock/" + payNo + "/confirm", Map.of(), token);
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

    private long readLong(String json, String path) {
        return ((Number) JsonPath.read(json, path)).longValue();
    }
}
