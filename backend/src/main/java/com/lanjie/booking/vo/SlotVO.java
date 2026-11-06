package com.lanjie.booking.vo;

public record SlotVO(Long id, Long courtId, String startAt, String endAt, int priceCents, String status) {
}
