package com.lanjie.booking.vo;

import java.time.LocalDate;

public record CalendarDayVO(LocalDate date, String openTime, String closeTime, long availableCount) {
}
