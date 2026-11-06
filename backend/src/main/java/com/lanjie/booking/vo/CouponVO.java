package com.lanjie.booking.vo;

public record CouponVO(Long id, Long templateId, String name, String type, int valueCents,
                       int percent, int maxDiscountCents, int minAmountCents,
                       String scope, String status, String expiresAt) {
}
