package com.lanjie.booking.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 退款阶梯纯函数：≥24h 全退 / 2–24h 退 70% / <2h 不退 */
class RefundLadderTest {

    private final RefundService service = new RefundService(null, null, null, null, null, null);

    @Test
    void fullRefundAt24hOrMore() {
        LocalDateTime now = LocalDateTime.now();
        assertEquals(100, service.refundRatio(now.plusHours(24), now));
        assertEquals(100, service.refundRatio(now.plusHours(72), now));
    }

    @Test
    void partialRefundBetween2hAnd24h() {
        LocalDateTime now = LocalDateTime.now();
        assertEquals(70, service.refundRatio(now.plusHours(23).plusMinutes(59), now));
        assertEquals(70, service.refundRatio(now.plusHours(2), now));
    }

    @Test
    void noRefundUnder2h() {
        LocalDateTime now = LocalDateTime.now();
        assertEquals(0, service.refundRatio(now.plusMinutes(119), now));
        assertEquals(0, service.refundRatio(now.minusMinutes(30), now));
    }
}
