package com.lanjie.booking.enums;

/** 订单状态：待支付 → 已确认 → 已完成 / 已取消 / 爽约 */
public enum BookingStatus {
    PENDING_PAYMENT, CONFIRMED, COMPLETED, CANCELLED, NO_SHOW
}
