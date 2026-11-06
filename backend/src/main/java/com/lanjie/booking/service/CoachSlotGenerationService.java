package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.entity.Coach;
import com.lanjie.booking.entity.CoachScheduleRule;
import com.lanjie.booking.entity.CoachSlot;
import com.lanjie.booking.enums.SlotStatus;
import com.lanjie.booking.mapper.CoachMapper;
import com.lanjie.booking.mapper.CoachScheduleRuleMapper;
import com.lanjie.booking.mapper.CoachSlotMapper;
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
 * 教练档期滚动生成：按每周规则生成未来 30 天、1 小时一格；(coach_id, start_at) 唯一键幂等。
 */
@Slf4j
@Service
public class CoachSlotGenerationService {

    public static final int DAYS_AHEAD = 30;

    private final CoachMapper coachMapper;
    private final CoachScheduleRuleMapper ruleMapper;
    private final CoachSlotMapper coachSlotMapper;

    public CoachSlotGenerationService(CoachMapper coachMapper, CoachScheduleRuleMapper ruleMapper,
                                      CoachSlotMapper coachSlotMapper) {
        this.coachMapper = coachMapper;
        this.ruleMapper = ruleMapper;
        this.coachSlotMapper = coachSlotMapper;
    }

    public void generateOnStartup() {
        int created = generateRange(LocalDate.now(), LocalDate.now().plusDays(DAYS_AHEAD - 1L));
        log.info("coach slot generation on startup: created={}", created);
    }

    @Scheduled(cron = "0 5 2 * * ?")
    public void generateNightly() {
        int created = generateRange(LocalDate.now(), LocalDate.now().plusDays(DAYS_AHEAD - 1L));
        log.info("coach slot generation nightly: created={}", created);
    }

    @Transactional
    public int generateRange(LocalDate from, LocalDate toInclusive) {
        List<Coach> coaches = coachMapper.selectList(new LambdaQueryWrapper<Coach>()
                .eq(Coach::getStatus, "ACTIVE"));
        int created = 0;
        for (LocalDate date = from; !date.isAfter(toInclusive); date = date.plusDays(1)) {
            for (Coach coach : coaches) {
                created += generateForCoachDay(coach, date);
            }
        }
        return created;
    }

    /** 教练改规则后重建：关掉该教练全部未售格子，再按新规则补齐 */
    @Transactional
    public void regenerateForCoach(Long coachId) {
        List<CoachSlot> open = coachSlotMapper.selectList(new LambdaQueryWrapper<CoachSlot>()
                .eq(CoachSlot::getCoachId, coachId)
                .in(CoachSlot::getStatus, SlotStatus.AVAILABLE, SlotStatus.CLOSED));
        for (CoachSlot slot : open) {
            slot.setStatus(SlotStatus.CLOSED);
            coachSlotMapper.updateById(slot);
        }
        Coach coach = coachMapper.selectById(coachId);
        if (coach != null && "ACTIVE".equals(coach.getStatus())) {
            generateForCoachDay(coach, LocalDate.now());
            for (LocalDate d = LocalDate.now().plusDays(1);
                 !d.isAfter(LocalDate.now().plusDays(DAYS_AHEAD - 1L)); d = d.plusDays(1)) {
                generateForCoachDay(coach, d);
            }
        }
    }

    private int generateForCoachDay(Coach coach, LocalDate date) {
        List<CoachScheduleRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<CoachScheduleRule>()
                .eq(CoachScheduleRule::getCoachId, coach.getId())
                .eq(CoachScheduleRule::getWeekday, date.getDayOfWeek().getValue())
                .eq(CoachScheduleRule::getStatus, "ACTIVE"));
        if (rules.isEmpty()) {
            return 0;
        }
        Set<LocalTime> existing = new HashSet<>();
        for (CoachSlot slot : coachSlotMapper.selectList(new LambdaQueryWrapper<CoachSlot>()
                .eq(CoachSlot::getCoachId, coach.getId())
                .apply("DATE(start_at) = {0}", date))) {
            existing.add(slot.getStartAt().toLocalTime());
        }
        int created = 0;
        for (CoachScheduleRule rule : rules) {
            for (LocalTime start = rule.getStartTime();
                 start.plusHours(1).compareTo(rule.getEndTime()) <= 0; start = start.plusHours(1)) {
                if (existing.contains(start)) {
                    continue;
                }
                CoachSlot slot = new CoachSlot();
                slot.setCoachId(coach.getId());
                slot.setStartAt(LocalDateTime.of(date, start));
                slot.setEndAt(LocalDateTime.of(date, start.plusHours(1)));
                slot.setStatus(SlotStatus.AVAILABLE);
                slot.setVersion(0);
                try {
                    coachSlotMapper.insert(slot);
                    created++;
                } catch (DuplicateKeyException e) {
                    // 并发生成唯一键兜底
                }
            }
        }
        return created;
    }
}
