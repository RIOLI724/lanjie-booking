package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.entity.Venue;
import com.lanjie.booking.entity.VenueHours;
import com.lanjie.booking.mapper.VenueHoursMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;

/** 营业时间解析：每周配置优先，未配置回落到场馆默认时间 */
@Service
public class VenueHoursService {

    private final VenueHoursMapper venueHoursMapper;

    public VenueHoursService(VenueHoursMapper venueHoursMapper) {
        this.venueHoursMapper = venueHoursMapper;
    }

    /** 返回 [open, close] */
    public LocalTime[] windowFor(Venue venue, LocalDate date) {
        VenueHours hours = venueHoursMapper.selectOne(new LambdaQueryWrapper<VenueHours>()
                .eq(VenueHours::getVenueId, venue.getId())
                .eq(VenueHours::getWeekday, date.getDayOfWeek().getValue())
                .last("LIMIT 1"));
        LocalTime open = hours != null ? hours.getOpenTime() : venue.getOpenTime();
        LocalTime close = hours != null ? hours.getCloseTime() : venue.getCloseTime();
        return new LocalTime[]{open, close};
    }
}
