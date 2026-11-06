package com.lanjie.booking.bootstrap;

import com.lanjie.booking.entity.Court;
import com.lanjie.booking.entity.PriceRule;
import com.lanjie.booking.entity.Venue;
import com.lanjie.booking.entity.VenueHours;
import com.lanjie.booking.enums.CourtStatus;
import com.lanjie.booking.enums.CourtType;
import com.lanjie.booking.enums.DayType;
import com.lanjie.booking.enums.VenueStatus;
import com.lanjie.booking.mapper.CourtMapper;
import com.lanjie.booking.mapper.PriceRuleMapper;
import com.lanjie.booking.mapper.VenueHoursMapper;
import com.lanjie.booking.mapper.VenueMapper;
import com.lanjie.booking.service.SlotGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

/**
 * 首次启动写入演示数据（城东体育馆 + 4 片场 + 营业时间 + 价格规则），然后补齐未来 30 天格子。
 * 幂等：已有场馆数据则跳过 seed，只补格子。
 */
@Slf4j
@Component
public class SeedDataRunner implements ApplicationRunner {

    private final VenueMapper venueMapper;
    private final VenueHoursMapper venueHoursMapper;
    private final CourtMapper courtMapper;
    private final PriceRuleMapper priceRuleMapper;
    private final SlotGenerationService slotGenerationService;

    public SeedDataRunner(VenueMapper venueMapper, VenueHoursMapper venueHoursMapper,
                          CourtMapper courtMapper, PriceRuleMapper priceRuleMapper,
                          SlotGenerationService slotGenerationService) {
        this.venueMapper = venueMapper;
        this.venueHoursMapper = venueHoursMapper;
        this.courtMapper = courtMapper;
        this.priceRuleMapper = priceRuleMapper;
        this.slotGenerationService = slotGenerationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (venueMapper.selectCount(null) == 0) {
            seed();
        }
        slotGenerationService.generateOnStartup();
    }

    private void seed() {
        Venue venue = new Venue();
        venue.setName("城东体育馆");
        venue.setAddress("城东区体育大道 8 号");
        venue.setPhone("0871-88886666");
        venue.setOpenTime(LocalTime.of(9, 0));
        venue.setCloseTime(LocalTime.of(22, 0));
        venue.setTimezone("Asia/Shanghai");
        venue.setStatus(VenueStatus.ACTIVE);
        venueMapper.insert(venue);

        for (int weekday = 1; weekday <= 7; weekday++) {
            VenueHours hours = new VenueHours();
            hours.setVenueId(venue.getId());
            hours.setWeekday(weekday);
            hours.setOpenTime(LocalTime.of(9, 0));
            hours.setCloseTime(LocalTime.of(22, 0));
            venueHoursMapper.insert(hours);
        }

        String[] names = {"1 号场", "2 号场", "3 号场", "4 号场"};
        for (int i = 0; i < names.length; i++) {
            Court court = new Court();
            court.setVenueId(venue.getId());
            court.setName(names[i]);
            court.setType(CourtType.FULL);
            court.setBasePriceCents(4000);
            court.setStatus(CourtStatus.ACTIVE);
            court.setSortOrder(i + 1);
            courtMapper.insert(court);
        }

        // 全馆价格规则：平日 09-18 ¥40 / 18-22 ¥70；周末与节假日 09-10 ¥40 / 10-22 ¥80
        seedRule(null, DayType.WEEKDAY, "09:00", "18:00", 4000, 1);
        seedRule(null, DayType.WEEKDAY, "18:00", "22:00", 7000, 2);
        seedRule(null, DayType.WEEKEND, "09:00", "10:00", 4000, 1);
        seedRule(null, DayType.WEEKEND, "10:00", "22:00", 8000, 2);
        seedRule(null, DayType.HOLIDAY, "09:00", "10:00", 4000, 1);
        seedRule(null, DayType.HOLIDAY, "10:00", "22:00", 8000, 2);
        log.info("seeded demo venue/courts/hours/price rules");
    }

    private void seedRule(Long courtId, DayType dayType, String start, String end, int priceCents, int priority) {
        PriceRule rule = new PriceRule();
        rule.setCourtId(courtId);
        rule.setDayType(dayType);
        rule.setStartTime(LocalTime.parse(start));
        rule.setEndTime(LocalTime.parse(end));
        rule.setPriceCents(priceCents);
        rule.setPriority(priority);
        rule.setStatus("ACTIVE");
        priceRuleMapper.insert(rule);
    }
}
