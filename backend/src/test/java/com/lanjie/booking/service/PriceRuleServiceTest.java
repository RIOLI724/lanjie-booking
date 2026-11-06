package com.lanjie.booking.service;

import com.lanjie.booking.entity.Court;
import com.lanjie.booking.entity.PriceRule;
import com.lanjie.booking.enums.CourtStatus;
import com.lanjie.booking.enums.CourtType;
import com.lanjie.booking.enums.DayType;
import com.lanjie.booking.mapper.PriceRuleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PriceRuleServiceTest {

    @Mock
    private PriceRuleMapper priceRuleMapper;

    private final Court court = court(4000);

    @Test
    void matchedRuleWins() {
        when(priceRuleMapper.selectOne(any())).thenReturn(rule(7000));
        PriceRuleService service = new PriceRuleService(priceRuleMapper);
        assertEquals(7000, service.resolvePrice(court, DayType.WEEKDAY, LocalTime.of(19, 0)));
    }

    @Test
    void fallsBackToCourtBasePrice() {
        when(priceRuleMapper.selectOne(any())).thenReturn(null);
        PriceRuleService service = new PriceRuleService(priceRuleMapper);
        assertEquals(4000, service.resolvePrice(court, DayType.WEEKDAY, LocalTime.of(19, 0)));
    }

    private Court court(int basePriceCents) {
        Court court = new Court();
        court.setId(1L);
        court.setVenueId(1L);
        court.setName("1 号场");
        court.setType(CourtType.FULL);
        court.setBasePriceCents(basePriceCents);
        court.setStatus(CourtStatus.ACTIVE);
        court.setSortOrder(1);
        return court;
    }

    private PriceRule rule(int priceCents) {
        PriceRule rule = new PriceRule();
        rule.setId(1L);
        rule.setCourtId(null);
        rule.setDayType(DayType.WEEKDAY);
        rule.setStartTime(LocalTime.of(18, 0));
        rule.setEndTime(LocalTime.of(22, 0));
        rule.setPriceCents(priceCents);
        rule.setPriority(2);
        rule.setStatus("ACTIVE");
        return rule;
    }
}
