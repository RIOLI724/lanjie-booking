package com.lanjie.booking.vo;

public record MemberVO(String levelName, int discountPct, double pointsRate,
                       long cumulativeRechargeCents, int pointsBalance) {
}
