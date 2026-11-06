package com.lanjie.booking.vo;

import java.util.List;

public record HoldVO(Long bookingId, String bookingNo, int payableCents,
                     String holdExpiresAt, List<ItemVO> items) {
}
