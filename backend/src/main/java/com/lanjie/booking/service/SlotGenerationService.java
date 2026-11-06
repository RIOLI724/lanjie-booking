package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.entity.Court;
import com.lanjie.booking.entity.Slot;
import com.lanjie.booking.entity.Venue;
import com.lanjie.booking.entity.VenueHours;
import com.lanjie.booking.enums.CourtStatus;
import com.lanjie.booking.enums.DayType;
import com.lanjie.booking.enums.SlotStatus;
import com.lanjie.booking.enums.VenueStatus;
import com.lanjie.booking.mapper.CourtMapper;
import com.lanjie.booking.mapper.SlotMapper;
import com.lanjie.booking.mapper.VenueMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 格子滚动生成：每天 02:00 补足未来 30 天，服务启动时也补一次；
 * (court_id, start_at) 唯一键兜底幂等，重复生成不会出重。
 */
@Slf4j
@Service
public class SlotGenerationService {

    public static final int DAYS_AHEAD = 30;

    private final VenueMapper venueMapper;
    private final CourtMapper courtMapper;
    private final SlotMapper slotMapper;
    private final VenueHoursService venueHoursService;
    private final DayTypeService dayTypeService;
    private final PriceRuleService priceRuleService;

    public SlotGenerationService(VenueMapper venueMapper, CourtMapper courtMapper, SlotMapper slotMapper,
                                 VenueHoursService venueHoursService, DayTypeService dayTypeService,
                                 PriceRuleService priceRuleService) {
        this.venueMapper = venueMapper;
        this.courtMapper = courtMapper;
        this.slotMapper = slotMapper;
        this.venueHoursService = venueHoursService;
        this.dayTypeService = dayTypeService;
        this.priceRuleService = priceRuleService;
    }

    /** 启动即补齐 */
    public void generateOnStartup() {
        int created = generateRange(LocalDate.now(), LocalDate.now().plusDays(DAYS_AHEAD - 1L));
        log.info("slot generation on startup: created={}", created);
    }

    @Scheduled(cron = "0 0 2 * * ?")
    public void generateNightly() {
        int created = generateRange(LocalDate.now(), LocalDate.now().plusDays(DAYS_AHEAD - 1L));
        log.info("slot generation nightly: created={}", created);
    }

    @Transactional
    public int generateRange(LocalDate from, LocalDate toInclusive) {
        List<Venue> venues = venueMapper.selectList(new LambdaQueryWrapper<Venue>()
                .eq(Venue::getStatus, VenueStatus.ACTIVE));
        List<Court> courts = courtMapper.selectList(new LambdaQueryWrapper<Court>()
                .eq(Court::getStatus, CourtStatus.ACTIVE)
                .orderByAsc(Court::getSortOrder));
        if (venues.isEmpty() || courts.isEmpty()) {
            return 0;
        }
        int created = 0;
        for (LocalDate date = from; !date.isAfter(toInclusive); date = date.plusDays(1)) {
            DayType dayType = dayTypeService.of(date);
            for (Venue venue : venues) {
                LocalTime[] window = venueHoursService.windowFor(venue, date);
                for (Court court : courtsOfVenue(courts, venue.getId())) {
                    created += generateForCourtDay(venue.getId(), court, date, window[0], window[1], dayType);
                }
            }
        }
        return created;
    }

    private int generateForCourtDay(Long venueId, Court court, LocalDate date,
                                    LocalTime open, LocalTime close, DayType dayType) {
        Set<LocalTime> existing = new HashSet<>();
        for (Slot slot : slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                .eq(Slot::getCourtId, court.getId())
                .eq(Slot::getSlotDate, date))) {
            existing.add(slot.getStartAt().toLocalTime());
        }
        int created = 0;
        for (LocalTime start = open; start.plusHours(1).compareTo(close) <= 0; start = start.plusHours(1)) {
            if (existing.contains(start)) {
                continue;
            }
            Slot slot = new Slot();
            slot.setVenueId(venueId);
            slot.setCourtId(court.getId());
            slot.setSlotDate(date);
            slot.setStartAt(LocalDateTime.of(date, start));
            slot.setEndAt(LocalDateTime.of(date, start.plusHours(1)));
            slot.setPriceCents(priceRuleService.resolvePrice(court, dayType, start));
            slot.setStatus(SlotStatus.AVAILABLE);
            slot.setVersion(0);
            try {
                slotMapper.insert(slot);
                created++;
            } catch (DuplicateKeyException e) {
                // 并发生成时唯一键兜底，跳过即可
            }
        }
        return created;
    }

    private List<Court> courtsOfVenue(List<Court> courts, Long venueId) {
        return courts.stream().filter(c -> venueId.equals(c.getVenueId())).toList();
    }
}
