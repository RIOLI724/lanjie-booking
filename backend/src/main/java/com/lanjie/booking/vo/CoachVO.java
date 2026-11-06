package com.lanjie.booking.vo;

import java.util.List;

public record CoachVO(Long id, String displayName, String avatarUrl, String bio, String specialties,
                      double rating, int ratingCount, int basePriceCents, List<ServiceVO> services) {

    public record ServiceVO(Long id, String type, String title, int priceCents,
                            boolean includesCourt, int minStudents, int maxStudents) {
    }
}
