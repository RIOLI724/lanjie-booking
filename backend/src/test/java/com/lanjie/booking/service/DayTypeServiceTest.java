package com.lanjie.booking.service;

import com.lanjie.booking.entity.Holiday;
import com.lanjie.booking.enums.DayType;
import com.lanjie.booking.enums.HolidayDateType;
import com.lanjie.booking.mapper.HolidayMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DayTypeServiceTest {

    @Mock
    private HolidayMapper holidayMapper;

    private DayTypeService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new DayTypeService(holidayMapper);
    }

    @Test
    void ordinaryWeekday() {
        // 2026-11-11 是周三
        when(holidayMapper.selectOne(any())).thenReturn(null);
        assertEquals(DayType.WEEKDAY, service.of(LocalDate.of(2026, 11, 11)));
    }

    @Test
    void weekend() {
        when(holidayMapper.selectOne(any())).thenReturn(null);
        assertEquals(DayType.WEEKEND, service.of(LocalDate.of(2026, 11, 14)));
        assertEquals(DayType.WEEKEND, service.of(LocalDate.of(2026, 11, 15)));
    }

    @Test
    void holidayOverridesWeekday() {
        when(holidayMapper.selectOne(any())).thenReturn(holiday(HolidayDateType.HOLIDAY));
        assertEquals(DayType.HOLIDAY, service.of(LocalDate.of(2026, 11, 11)));
    }

    @Test
    void swappedWorkdayCountsAsWeekday() {
        // 调休上班的周六按工作日算价
        when(holidayMapper.selectOne(any())).thenReturn(holiday(HolidayDateType.WORKDAY));
        assertEquals(DayType.WEEKDAY, service.of(LocalDate.of(2026, 11, 14)));
    }

    private Holiday holiday(HolidayDateType type) {
        Holiday holiday = new Holiday();
        holiday.setHolidayDate(LocalDate.of(2026, 11, 11));
        holiday.setDateType(type);
        holiday.setName("测试");
        return holiday;
    }
}
