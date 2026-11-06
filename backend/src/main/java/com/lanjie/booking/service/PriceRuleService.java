package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.entity.Court;
import com.lanjie.booking.entity.PriceRule;
import com.lanjie.booking.enums.DayType;
import com.lanjie.booking.mapper.PriceRuleMapper;
import org.springframework.stereotype.Service;

import java.time.LocalTime;

/**
 * 价格解析：场地专属规则优先于全馆规则，priority 大者优先，再按 id 小者稳定排序；
 * 全不命中回落到场地基础价。价格在生成格子时快照，改价不追溯已生成格子。
 */
@Service
public class PriceRuleService {

    private final PriceRuleMapper priceRuleMapper;

    public PriceRuleService(PriceRuleMapper priceRuleMapper) {
        this.priceRuleMapper = priceRuleMapper;
    }

    public int resolvePrice(Court court, DayType dayType, LocalTime hourStart) {
        PriceRule rule = priceRuleMapper.selectOne(new LambdaQueryWrapper<PriceRule>()
                .eq(PriceRule::getStatus, "ACTIVE")
                .eq(PriceRule::getDayType, dayType)
                .and(w -> w.eq(PriceRule::getCourtId, court.getId()).or().isNull(PriceRule::getCourtId))
                .le(PriceRule::getStartTime, hourStart)
                .ge(PriceRule::getEndTime, hourStart.plusHours(1))
                .orderByDesc(PriceRule::getPriority)
                .orderByAsc(PriceRule::getId)
                .last("LIMIT 1"));
        return rule != null ? rule.getPriceCents() : court.getBasePriceCents();
    }
}
