package com.lanjie.booking.vo;

public record CoachPackageVO(Long id, String coachName, String serviceTitle, int totalSessions,
                             int usedSessions, int remainingSessions, int priceCents,
                             String expiresAt, String status) {
}
