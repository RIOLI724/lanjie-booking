package com.lanjie.booking.vo;

import java.util.List;

public record BookingVO(Long id, String bookingNo, String type, String status,
                        int listAmountCents, int payableCents, int paidCents,
                        String holdExpiresAt, String createdAt,
                        int rescheduleCount, int refundQuoteCents, List<ItemVO> items) {
}
