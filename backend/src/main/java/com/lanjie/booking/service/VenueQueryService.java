package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.entity.Court;
import com.lanjie.booking.entity.Slot;
import com.lanjie.booking.entity.Venue;
import com.lanjie.booking.enums.CourtStatus;
import com.lanjie.booking.enums.SlotStatus;
import com.lanjie.booking.enums.VenueStatus;
import com.lanjie.booking.mapper.CourtMapper;
import com.lanjie.booking.mapper.SlotMapper;
import com.lanjie.booking.mapper.VenueMapper;
import com.lanjie.booking.vo.CalendarDayVO;
import com.lanjie.booking.vo.CourtVO;
import com.lanjie.booking.vo.SlotVO;
import com.lanjie.booking.vo.VenueVO;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 场馆/场地/格子查询（订场表的数据源） */
@Service
public class VenueQueryService {

    private final VenueMapper venueMapper;
    private final CourtMapper courtMapper;
    private final SlotMapper slotMapper;
    private final VenueHoursService venueHoursService;

    public VenueQueryService(VenueMapper venueMapper, CourtMapper courtMapper,
                             SlotMapper slotMapper, VenueHoursService venueHoursService) {
        this.venueMapper = venueMapper;
        this.courtMapper = courtMapper;
        this.slotMapper = slotMapper;
        this.venueHoursService = venueHoursService;
    }

    public VenueVO venue() {
        Venue venue = venueMapper.selectOne(new LambdaQueryWrapper<Venue>()
                .eq(Venue::getStatus, VenueStatus.ACTIVE)
                .orderByAsc(Venue::getId)
                .last("LIMIT 1"));
        if (venue == null) {
            return null;
        }
        return new VenueVO(venue.getName(), venue.getAddress(), venue.getPhone(),
                venue.getOpenTime().toString(), venue.getCloseTime().toString());
    }

    public List<CourtVO> courts() {
        return courtMapper.selectList(new LambdaQueryWrapper<Court>()
                .eq(Court::getStatus, CourtStatus.ACTIVE)
                .orderByAsc(Court::getSortOrder))
                .stream()
                .map(c -> new CourtVO(c.getId(), c.getName(), c.getStatus().name()))
                .toList();
    }

    public List<SlotVO> slotsOfDate(LocalDate date) {
        List<Court> courts = courtMapper.selectList(new LambdaQueryWrapper<Court>()
                .eq(Court::getStatus, CourtStatus.ACTIVE)
                .orderByAsc(Court::getSortOrder));
        Map<Long, Integer> courtOrder = new HashMap<>();
        for (int i = 0; i < courts.size(); i++) {
            courtOrder.put(courts.get(i).getId(), i);
        }
        return slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                .eq(Slot::getSlotDate, date)
                .orderByAsc(Slot::getStartAt))
                .stream()
                .filter(s -> courtOrder.containsKey(s.getCourtId()))
                .sorted(Comparator.comparing((Slot s) -> courtOrder.get(s.getCourtId()))
                        .thenComparing(Slot::getStartAt))
                .map(s -> new SlotVO(s.getId(), s.getCourtId(),
                        s.getStartAt().toLocalTime().toString(),
                        s.getEndAt().toLocalTime().toString(),
                        s.getPriceCents(), s.getStatus().name()))
                .toList();
    }

    public List<CalendarDayVO> calendar(LocalDate from, LocalDate toInclusive) {
        Venue venue = venueMapper.selectOne(new LambdaQueryWrapper<Venue>()
                .eq(Venue::getStatus, VenueStatus.ACTIVE)
                .orderByAsc(Venue::getId)
                .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        List<CalendarDayVO> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(toInclusive); date = date.plusDays(1)) {
            LocalTime[] window = venue != null ? venueHoursService.windowFor(venue, date)
                    : new LocalTime[]{LocalTime.of(9, 0), LocalTime.of(22, 0)};
            long available = slotMapper.selectCount(new LambdaQueryWrapper<Slot>()
                    .eq(Slot::getSlotDate, date)
                    .eq(Slot::getStatus, SlotStatus.AVAILABLE)
                    .ge(Slot::getStartAt, date.equals(now.toLocalDate()) ? now : LocalDateTime.of(date, window[0])));
            days.add(new CalendarDayVO(date, window[0].toString(), window[1].toString(), available));
        }
        return days;
    }
}
