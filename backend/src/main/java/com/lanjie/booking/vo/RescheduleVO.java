package com.lanjie.booking.vo;

import java.util.List;

/** 改期结果：diffCents > 0 时需补差价（payNo 非空），≤ 0 已即时生效并退差价 */
public record RescheduleVO(Long bookingId, int diffCents, String payNo, List<ItemVO> newItems) {
}
