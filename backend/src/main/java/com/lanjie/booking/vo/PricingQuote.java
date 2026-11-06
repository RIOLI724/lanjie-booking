package com.lanjie.booking.vo;

/** 计价结果：会员折扣与券二选一（自动选优），积分再抵不超过 20%，最低支付 1 分 */
public record PricingQuote(int listCents, int memberDiscountCents, int couponDiscountCents,
                           int pointsDiscountCents, int pointsUsed, Long couponId,
                           int discountCents, int payableCents) {
}
