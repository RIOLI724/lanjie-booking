package com.lanjie.booking.enums;

/** 课包状态：待支付 → 生效 → 用完/过期/已退 */
public enum PackageStatus {
    PENDING_PAYMENT, ACTIVE, EXHAUSTED, EXPIRED, REFUNDED
}
