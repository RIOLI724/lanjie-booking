package com.lanjie.booking.enums;

/**
 * 格子状态机：AVAILABLE → HELD(15 分钟) → BOOKED；HELD 过期自动回 AVAILABLE；CLOSED 仅管理员可设。
 */
public enum SlotStatus {
    AVAILABLE, HELD, BOOKED, CLOSED
}
