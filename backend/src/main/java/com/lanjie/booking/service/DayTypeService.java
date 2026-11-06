package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.entity.Holiday;
import com.lanjie.booking.enums.DayType;
import com.lanjie.booking.enums.HolidayDateType;
import com.lanjie.booking.mapper.HolidayMapper;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 日期类型判定：节假日表优先（HOLIDAY=休，WORKDAY=调休上班按工作日），否则按周六日。
 */
@Service
public class DayTypeService {

    private final HolidayMapper holidayMapper;

    public DayTypeService(HolidayMapper holidayMapper) {
        this.holidayMapper = holidayMapper;
    }

    public DayType of(LocalDate date) {
        Holiday holiday = holidayMapper.selectOne(new LambdaQueryWrapper<Holiday>()
                .eq(Holiday::getHolidayDate, date)
                .last("LIMIT 1"));
        if (holiday != null) {
            return holiday.getDateType() == HolidayDateType.HOLIDAY ? DayType.HOLIDAY : DayType.WEEKDAY;
        }
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY ? DayType.WEEKEND : DayType.WEEKDAY;
    }
}
