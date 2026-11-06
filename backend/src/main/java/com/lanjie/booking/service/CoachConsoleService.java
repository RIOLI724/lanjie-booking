package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.entity.BookingItem;
import com.lanjie.booking.entity.Coach;
import com.lanjie.booking.entity.CoachScheduleRule;
import com.lanjie.booking.entity.CoachSlot;
import com.lanjie.booking.entity.CoachTimeOff;
import com.lanjie.booking.enums.BookingStatus;
import com.lanjie.booking.enums.SlotStatus;
import com.lanjie.booking.mapper.BookingItemMapper;
import com.lanjie.booking.mapper.BookingMapper;
import com.lanjie.booking.mapper.CoachScheduleRuleMapper;
import com.lanjie.booking.mapper.CoachSlotMapper;
import com.lanjie.booking.mapper.CoachTimeOffMapper;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.vo.CoachSlotVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 教练端：每周规则（改完即重建未来 30 天档期）、请假（关未售格、列出已售）、课表与分成。
 */
@Slf4j
@Service
public class CoachConsoleService {

    private final CoachQueryService coachQueryService;
    private final CoachScheduleRuleMapper ruleMapper;
    private final CoachSlotMapper coachSlotMapper;
    private final CoachTimeOffMapper timeOffMapper;
    private final BookingMapper bookingMapper;
    private final BookingItemMapper bookingItemMapper;
    private final CoachSlotGenerationService generationService;

    public CoachConsoleService(CoachQueryService coachQueryService, CoachScheduleRuleMapper ruleMapper,
                               CoachSlotMapper coachSlotMapper, CoachTimeOffMapper timeOffMapper,
                               BookingMapper bookingMapper, BookingItemMapper bookingItemMapper,
                               CoachSlotGenerationService generationService) {
        this.coachQueryService = coachQueryService;
        this.ruleMapper = ruleMapper;
        this.coachSlotMapper = coachSlotMapper;
        this.timeOffMapper = timeOffMapper;
        this.bookingMapper = bookingMapper;
        this.bookingItemMapper = bookingItemMapper;
        this.generationService = generationService;
    }

    private Coach coachOf(LoginUser loginUser) {
        if (!"COACH".equals(loginUser.role()) && !"ADMIN".equals(loginUser.role())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        Coach coach = coachQueryService.coachByUserId(loginUser.userId());
        if (coach == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号未绑定教练");
        }
        return coach;
    }

    public List<CoachScheduleRule> schedule(LoginUser loginUser) {
        Coach coach = coachOf(loginUser);
        return ruleMapper.selectList(new LambdaQueryWrapper<CoachScheduleRule>()
                .eq(CoachScheduleRule::getCoachId, coach.getId())
                .eq(CoachScheduleRule::getStatus, "ACTIVE")
                .orderByAsc(CoachScheduleRule::getWeekday)
                .orderByAsc(CoachScheduleRule::getStartTime));
    }

    /** 改规则：整组替换并重建未来档期（只影响未售格子，已售出的保持不变） */
    @Transactional
    public List<CoachScheduleRule> updateSchedule(LoginUser loginUser, List<CoachScheduleRule> rules) {
        Coach coach = coachOf(loginUser);
        ruleMapper.delete(new LambdaQueryWrapper<CoachScheduleRule>()
                .eq(CoachScheduleRule::getCoachId, coach.getId()));
        for (CoachScheduleRule rule : rules) {
            if (rule.getWeekday() == null || rule.getStartTime() == null || rule.getEndTime() == null
                    || !rule.getStartTime().isBefore(rule.getEndTime())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "规则不完整");
            }
            CoachScheduleRule entity = new CoachScheduleRule();
            entity.setCoachId(coach.getId());
            entity.setWeekday(rule.getWeekday());
            entity.setStartTime(rule.getStartTime());
            entity.setEndTime(rule.getEndTime());
            entity.setStatus("ACTIVE");
            ruleMapper.insert(entity);
        }
        generationService.regenerateForCoach(coach.getId());
        return schedule(loginUser);
    }

    /** 请假：关闭区间内未售格子；已售出的不动，返回已售数量供前端提示 */
    @Transactional
    public int timeOff(LoginUser loginUser, LocalDateTime startAt, LocalDateTime endAt, String reason) {
        Coach coach = coachOf(loginUser);
        if (!startAt.isBefore(endAt) || startAt.isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请假时间不正确");
        }
        CoachTimeOff off = new CoachTimeOff();
        off.setCoachId(coach.getId());
        off.setStartAt(startAt);
        off.setEndAt(endAt);
        off.setReason(reason == null ? "" : reason);
        timeOffMapper.insert(off);
        int closed = coachSlotMapper.closeRange(coach.getId(), startAt, endAt);
        long sold = coachSlotMapper.selectCount(new LambdaQueryWrapper<CoachSlot>()
                .eq(CoachSlot::getCoachId, coach.getId())
                .in(CoachSlot::getStatus, SlotStatus.HELD, SlotStatus.BOOKED)
                .ge(CoachSlot::getStartAt, startAt)
                .le(CoachSlot::getEndAt, endAt));
        log.info("coach time off: coach={} closed={} sold={}", coach.getId(), closed, sold);
        return (int) sold;
    }

    @Transactional
    public void cancelTimeOff(LoginUser loginUser, Long timeOffId) {
        Coach coach = coachOf(loginUser);
        CoachTimeOff off = timeOffMapper.selectById(timeOffId);
        if (off == null || !off.getCoachId().equals(coach.getId())) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND, "请假记录不存在");
        }
        coachSlotMapper.reopenRange(coach.getId(), off.getStartAt(), off.getEndAt());
        timeOffMapper.deleteById(timeOffId);
    }

    /** 未来 7 天课表 */
    public List<CoachSlotVO> lessons(LoginUser loginUser) {
        Coach coach = coachOf(loginUser);
        LocalDateTime from = LocalDateTime.now();
        LocalDateTime to = from.plusDays(7);
        return coachSlotMapper.selectList(new LambdaQueryWrapper<CoachSlot>()
                .eq(CoachSlot::getCoachId, coach.getId())
                .in(CoachSlot::getStatus, SlotStatus.BOOKED, SlotStatus.HELD)
                .ge(CoachSlot::getStartAt, from)
                .le(CoachSlot::getStartAt, to)
                .orderByAsc(CoachSlot::getStartAt))
                .stream()
                .map(s -> new CoachSlotVO(s.getId(), s.getStartAt().toString(),
                        s.getEndAt().toString(), s.getStatus().name()))
                .toList();
    }

    /** 本月分成：教练课实付 × (1 - 平台抽成) */
    public long monthEarnings(LoginUser loginUser) {
        Coach coach = coachOf(loginUser);
        var bookings = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getType, com.lanjie.booking.enums.BookingType.COACH)
                .in(Booking::getStatus, BookingStatus.CONFIRMED.name(), "COMPLETED")
                .apply("DATE_FORMAT(created_at, '%Y-%m') = {0}",
                        LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"))));
        long gross = 0;
        for (Booking booking : bookings) {
            BookingItem item = bookingItemMapper.selectOne(new LambdaQueryWrapper<BookingItem>()
                    .eq(BookingItem::getBookingId, booking.getId())
                    .eq(BookingItem::getItemType, com.lanjie.booking.enums.BookingItemType.COACH_SESSION)
                    .last("LIMIT 1"));
            if (item == null || !isMySlot(coach.getId(), item.getRefId())) {
                continue;
            }
            gross += booking.getPaidCents();
        }
        double rate = coach.getCommissionRate().doubleValue();
        return Math.round(gross * (1 - rate));
    }

    private boolean isMySlot(Long coachId, Long coachSlotId) {
        CoachSlot slot = coachSlotMapper.selectById(coachSlotId);
        return slot != null && slot.getCoachId().equals(coachId);
    }
}
