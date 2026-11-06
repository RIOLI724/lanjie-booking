package com.lanjie.booking.vo;

public record GroupClassVO(Long id, String title, String coachName, String startAt, String endAt,
                           int minStudents, int maxStudents, int priceCents,
                           int enrolledCount, String status) {
}
