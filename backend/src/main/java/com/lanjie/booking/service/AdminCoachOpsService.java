package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.entity.Coach;
import com.lanjie.booking.entity.CoachScheduleRule;
import com.lanjie.booking.entity.CoachService;
import com.lanjie.booking.entity.CoachSlot;
import com.lanjie.booking.entity.CoachTimeOff;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.enums.BookingStatus;
import com.lanjie.booking.enums.BookingType;
import com.lanjie.booking.enums.CoachServiceType;
import com.lanjie.booking.enums.SlotStatus;
import com.lanjie.booking.enums.UserRole;
import com.lanjie.booking.enums.UserStatus;
import com.lanjie.booking.mapper.BookingMapper;
import com.lanjie.booking.mapper.CoachMapper;
import com.lanjie.booking.mapper.CoachPackageMapper;
import com.lanjie.booking.mapper.CoachScheduleRuleMapper;
import com.lanjie.booking.mapper.CoachServiceMapper;
import com.lanjie.booking.mapper.CoachSlotMapper;
import com.lanjie.booking.mapper.CoachTimeOffMapper;
import com.lanjie.booking.mapper.SlotMapper;
import com.lanjie.booking.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 前台对教练侧的操作：入驻/编辑、代排周班表、代请假。
 * 请假涉及已售课程时，先返回受影响订单，确认后按场馆原因全退。
 */
@Slf4j
@Service
public class AdminCoachOpsService {

    private static final BigDecimal DEFAULT_COMMISSION = new BigDecimal("0.200");

    private final UserMapper userMapper;
    private final CoachMapper coachMapper;
    private final CoachServiceMapper coachServiceMapper;
    private final CoachScheduleRuleMapper ruleMapper;
    private final CoachSlotMapper coachSlotMapper;
    private final CoachTimeOffMapper timeOffMapper;
    private final BookingMapper bookingMapper;
    private final CoachPackageMapper coachPackageMapper;
    private final SlotMapper slotMapper;
    private final PasswordEncoder passwordEncoder;
    private final CoachSlotGenerationService generationService;
    private final RefundService refundService;
    private final BookingAuditService audit;

    public AdminCoachOpsService(UserMapper userMapper, CoachMapper coachMapper,
                                CoachServiceMapper coachServiceMapper, CoachScheduleRuleMapper ruleMapper,
                                CoachSlotMapper coachSlotMapper, CoachTimeOffMapper timeOffMapper,
                                BookingMapper bookingMapper, CoachPackageMapper coachPackageMapper,
                                SlotMapper slotMapper, PasswordEncoder passwordEncoder,
                                CoachSlotGenerationService generationService,
                                RefundService refundService, BookingAuditService audit) {
        this.userMapper = userMapper;
        this.coachMapper = coachMapper;
        this.coachServiceMapper = coachServiceMapper;
        this.ruleMapper = ruleMapper;
        this.coachSlotMapper = coachSlotMapper;
        this.timeOffMapper = timeOffMapper;
        this.bookingMapper = bookingMapper;
        this.coachPackageMapper = coachPackageMapper;
        this.slotMapper = slotMapper;
        this.passwordEncoder = passwordEncoder;
        this.generationService = generationService;
        this.refundService = refundService;
        this.audit = audit;
    }

    public List<Map<String, Object>> coaches() {
        List<Coach> coaches = coachMapper.selectList(new LambdaQueryWrapper<Coach>()
                .orderByAsc(Coach::getSortOrder).orderByAsc(Coach::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Coach coach : coaches) {
            User user = userMapper.selectById(coach.getUserId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", coach.getId());
            row.put("userId", coach.getUserId());
            row.put("displayName", coach.getDisplayName());
            row.put("phone", user != null ? user.getPhone() : "");
            row.put("bio", coach.getBio());
            row.put("specialties", coach.getSpecialties());
            row.put("basePriceCents", coach.getBasePriceCents());
            row.put("commissionRate", coach.getCommissionRate());
            row.put("rating", coach.getRating());
            row.put("ratingCount", coach.getRatingCount());
            row.put("status", coach.getStatus());
            row.put("userIdStatus", user != null ? user.getStatus().name() : "");
            result.add(row);
        }
        return result;
    }

    @Transactional
    public Long createCoach(String phone, String password, String displayName, String specialties,
                            int basePriceCents, String bio, BigDecimal commissionRate) {
        if (phone == null || !phone.matches("^1\\d{10}$")) {
            throw new BusinessException(ErrorCode.PHONE_INVALID);
        }
        if (password == null || password.length() < 6) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "初始密码至少 6 位");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "教练姓名不能为空");
        }
        if (basePriceCents <= 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "课时价必须大于 0");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getPhone, phone)) > 0) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
        }

        User user = new User();
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setNickname(displayName);
        user.setRole(UserRole.COACH);
        user.setStatus(UserStatus.ACTIVE);
        user.setCumulativeRechargeCents(0L);
        userMapper.insert(user);

        Coach coach = new Coach();
        coach.setUserId(user.getId());
        coach.setDisplayName(displayName);
        coach.setBio(bio == null ? "" : bio);
        coach.setSpecialties(specialties == null ? "" : specialties);
        coach.setRating(new BigDecimal("5.0"));
        coach.setRatingCount(0);
        coach.setBasePriceCents(basePriceCents);
        coach.setCommissionRate(normalizeCommission(commissionRate));
        coach.setStatus("ACTIVE");
        coach.setSortOrder(coachMapper.selectCount(null).intValue() + 1);
        coachMapper.insert(coach);

        // 默认全天可约，前台可在排班里改
        for (int weekday = 1; weekday <= 7; weekday++) {
            CoachScheduleRule rule = new CoachScheduleRule();
            rule.setCoachId(coach.getId());
            rule.setWeekday(weekday);
            rule.setStartTime(LocalTime.of(9, 0));
            rule.setEndTime(LocalTime.of(22, 0));
            rule.setStatus("ACTIVE");
            ruleMapper.insert(rule);
        }

        CoachService privateService = new CoachService();
        privateService.setCoachId(coach.getId());
        privateService.setType(CoachServiceType.PRIVATE);
        privateService.setTitle("私教单节（含场地）");
        privateService.setDurationMin(60);
        privateService.setPriceCents(basePriceCents);
        privateService.setIncludesCourt(true);
        privateService.setMinStudents(1);
        privateService.setMaxStudents(1);
        privateService.setStatus("ACTIVE");
        coachServiceMapper.insert(privateService);

        generationService.regenerateForCoach(coach.getId());
        log.info("coach created by admin: coach={} user={}", coach.getId(), user.getId());
        return coach.getId();
    }

    @Transactional
    public void updateCoach(Long coachId, String displayName, String specialties, Integer basePriceCents,
                            String bio, BigDecimal commissionRate, String status) {
        Coach coach = requireCoach(coachId);
        if (displayName != null && !displayName.isBlank()) {
            coach.setDisplayName(displayName);
        }
        if (specialties != null) {
            coach.setSpecialties(specialties);
        }
        if (bio != null) {
            coach.setBio(bio);
        }
        if (basePriceCents != null) {
            if (basePriceCents <= 0) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "课时价必须大于 0");
            }
            coach.setBasePriceCents(basePriceCents);
        }
        if (commissionRate != null) {
            coach.setCommissionRate(normalizeCommission(commissionRate));
        }
        if (status != null) {
            if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "教练状态不合法");
            }
            coach.setStatus(status);
        }
        coachMapper.updateById(coach);

        User user = userMapper.selectById(coach.getUserId());
        if (user != null) {
            user.setNickname(coach.getDisplayName());
            userMapper.updateById(user);
        }
        if (basePriceCents != null) {
            for (CoachService service : coachServiceMapper.selectList(new LambdaQueryWrapper<CoachService>()
                    .eq(CoachService::getCoachId, coachId)
                    .eq(CoachService::getType, CoachServiceType.PRIVATE))) {
                service.setPriceCents(basePriceCents);
                coachServiceMapper.updateById(service);
            }
        }
        if ("ACTIVE".equals(coach.getStatus())) {
            generationService.regenerateForCoach(coachId);
            reapplyTimeOffs(coachId);
        } else {
            coachSlotMapper.update(null, new LambdaUpdateWrapper<CoachSlot>()
                    .eq(CoachSlot::getCoachId, coachId)
                    .eq(CoachSlot::getStatus, SlotStatus.AVAILABLE)
                    .set(CoachSlot::getStatus, SlotStatus.CLOSED));
        }
    }

    public Map<String, Object> schedule(Long coachId) {
        Coach coach = requireCoach(coachId);
        List<CoachScheduleRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<CoachScheduleRule>()
                .eq(CoachScheduleRule::getCoachId, coachId)
                .eq(CoachScheduleRule::getStatus, "ACTIVE")
                .orderByAsc(CoachScheduleRule::getWeekday)
                .orderByAsc(CoachScheduleRule::getStartTime));
        List<CoachTimeOff> timeOffs = timeOffMapper.selectList(new LambdaQueryWrapper<CoachTimeOff>()
                .eq(CoachTimeOff::getCoachId, coachId)
                .ge(CoachTimeOff::getEndAt, LocalDateTime.now())
                .orderByAsc(CoachTimeOff::getStartAt));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("coachId", coach.getId());
        data.put("coachName", coach.getDisplayName());
        data.put("status", coach.getStatus());
        data.put("rules", rules);
        data.put("timeOffs", timeOffs);
        data.put("upcoming", upcomingSlots(coachId, LocalDate.now(), 7));
        return data;
    }

    /** 整组替换周规则并重建未来 30 天档期，随后把请假区间重新关掉 */
    @Transactional
    public void updateSchedule(Long coachId, List<CoachScheduleRule> rules) {
        requireCoach(coachId);
        ruleMapper.delete(new LambdaQueryWrapper<CoachScheduleRule>()
                .eq(CoachScheduleRule::getCoachId, coachId));
        for (CoachScheduleRule rule : rules) {
            if (rule.getWeekday() == null || rule.getWeekday() < 1 || rule.getWeekday() > 7
                    || rule.getStartTime() == null || rule.getEndTime() == null
                    || !rule.getStartTime().isBefore(rule.getEndTime())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "排班规则不完整或时间段不正确");
            }
            CoachScheduleRule entity = new CoachScheduleRule();
            entity.setCoachId(coachId);
            entity.setWeekday(rule.getWeekday());
            entity.setStartTime(rule.getStartTime());
            entity.setEndTime(rule.getEndTime());
            entity.setStatus("ACTIVE");
            ruleMapper.insert(entity);
        }
        generationService.regenerateForCoach(coachId);
        reapplyTimeOffs(coachId);
    }

    /**
     * 代请假：occupancy 先列出区间内已售课程；force=false 时不动钱，
     * force=true 时逐单按场馆原因全退/退课时并释放资源。
     */
    @Transactional
    public Map<String, Object> timeOff(Long coachId, LocalDateTime startAt, LocalDateTime endAt,
                                       String reason, boolean force) {
        Coach coach = requireCoach(coachId);
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)
                || startAt.isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请假时间不正确");
        }
        String safeReason = reason == null || reason.isBlank() ? "教练请假" : reason;

        List<CoachSlot> occupied = coachSlotMapper.selectList(new LambdaQueryWrapper<CoachSlot>()
                .eq(CoachSlot::getCoachId, coachId)
                .eq(CoachSlot::getStatus, SlotStatus.BOOKED)
                .ge(CoachSlot::getStartAt, startAt)
                .lt(CoachSlot::getStartAt, endAt)
                .orderByAsc(CoachSlot::getStartAt));
        Set<Long> bookingIds = new LinkedHashSet<>();
        List<Map<String, Object>> affected = new ArrayList<>();
        for (CoachSlot slot : occupied) {
            if (!"BOOKING".equals(slot.getOwnerType()) || slot.getOwnerId() == null
                    || !bookingIds.add(slot.getOwnerId())) {
                continue;
            }
            Booking booking = bookingMapper.selectById(slot.getOwnerId());
            if (booking == null || (booking.getStatus() != BookingStatus.CONFIRMED
                    && booking.getStatus() != BookingStatus.PENDING_PAYMENT)) {
                continue;
            }
            User user = userMapper.selectById(booking.getUserId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bookingId", booking.getId());
            row.put("userName", user != null ? user.getNickname() : "");
            row.put("userPhone", user != null ? user.getPhone() : "");
            row.put("startAt", slot.getStartAt());
            row.put("paidCents", booking.getPaidCents());
            row.put("status", booking.getStatus().name());
            affected.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        if (!force && !affected.isEmpty()) {
            data.put("blocked", true);
            data.put("affected", affected);
            data.put("message", "请假区间内还有 " + affected.size() + " 节课已售，确认后将全额退款/退回课时");
            return data;
        }

        for (Map<String, Object> row : affected) {
            cancelCoachBooking((Long) row.get("bookingId"), safeReason);
        }
        CoachTimeOff off = new CoachTimeOff();
        off.setCoachId(coachId);
        off.setStartAt(startAt);
        off.setEndAt(endAt);
        off.setReason(safeReason);
        timeOffMapper.insert(off);
        int closed = coachSlotMapper.closeRange(coachId, startAt, endAt);

        data.put("blocked", false);
        data.put("cancelled", affected);
        data.put("closedSlots", closed);
        data.put("timeOffId", off.getId());
        data.put("coachName", coach.getDisplayName());
        return data;
    }

    @Transactional
    public void cancelTimeOff(Long coachId, Long timeOffId) {
        requireCoach(coachId);
        CoachTimeOff off = timeOffMapper.selectById(timeOffId);
        if (off == null || !off.getCoachId().equals(coachId)) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND, "请假记录不存在");
        }
        coachSlotMapper.reopenRange(coachId, off.getStartAt(), off.getEndAt());
        timeOffMapper.deleteById(timeOffId);
    }

    public List<Map<String, Object>> upcomingSlots(Long coachId, LocalDate from, int days) {
        requireCoach(coachId);
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = from.plusDays(days).atStartOfDay();
        List<CoachSlot> slots = coachSlotMapper.selectList(new LambdaQueryWrapper<CoachSlot>()
                .eq(CoachSlot::getCoachId, coachId)
                .in(CoachSlot::getStatus, SlotStatus.AVAILABLE, SlotStatus.BOOKED, SlotStatus.HELD)
                .ge(CoachSlot::getStartAt, start)
                .lt(CoachSlot::getStartAt, end)
                .orderByAsc(CoachSlot::getStartAt)
                .last("LIMIT 200"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (CoachSlot slot : slots) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", slot.getId());
            row.put("startAt", slot.getStartAt());
            row.put("endAt", slot.getEndAt());
            row.put("status", slot.getStatus().name());
            if ("BOOKING".equals(slot.getOwnerType()) && slot.getOwnerId() != null) {
                Booking booking = bookingMapper.selectById(slot.getOwnerId());
                User user = booking != null ? userMapper.selectById(booking.getUserId()) : null;
                row.put("bookingId", booking != null ? booking.getId() : null);
                row.put("userName", user != null ? user.getNickname() : "");
            }
            result.add(row);
        }
        return result;
    }

    public List<CoachService> services(Long coachId) {
        requireCoach(coachId);
        return coachServiceMapper.selectList(new LambdaQueryWrapper<CoachService>()
                .eq(CoachService::getCoachId, coachId)
                .eq(CoachService::getStatus, "ACTIVE")
                .orderByAsc(CoachService::getId));
    }

    private void cancelCoachBooking(Long bookingId, String reason) {
        Booking booking = bookingMapper.selectById(bookingId);
        if (booking == null || booking.getType() != BookingType.COACH) {
            return;
        }
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            if (bookingMapper.markCancelledConfirmed(bookingId, "ADMIN_TIME_OFF") == 0) {
                return;
            }
            if (booking.getPackageId() != null) {
                coachPackageMapper.restoreSession(booking.getPackageId());
            } else if (booking.getPaidCents() != null && booking.getPaidCents() > 0) {
                refundService.refundForReschedule(booking, booking.getPaidCents(), reason);
            }
        } else if (booking.getStatus() == BookingStatus.PENDING_PAYMENT) {
            if (bookingMapper.markCancelled(bookingId, "ADMIN_TIME_OFF") == 0) {
                return;
            }
        } else {
            return;
        }
        coachSlotMapper.releaseBookedByBooking(bookingId);
        coachSlotMapper.releaseByBooking(bookingId);
        slotMapper.releaseBookedByBooking(bookingId);
        slotMapper.releaseByBooking(bookingId);
        audit.write(bookingId, booking.getStatus().name(), BookingStatus.CANCELLED.name(),
                "ADMIN", "前台代请假：" + reason);
    }

    private void reapplyTimeOffs(Long coachId) {
        List<CoachTimeOff> offs = timeOffMapper.selectList(new LambdaQueryWrapper<CoachTimeOff>()
                .eq(CoachTimeOff::getCoachId, coachId)
                .ge(CoachTimeOff::getEndAt, LocalDateTime.now()));
        for (CoachTimeOff off : offs) {
            coachSlotMapper.closeRange(coachId, off.getStartAt(), off.getEndAt());
        }
    }

    private Coach requireCoach(Long coachId) {
        Coach coach = coachMapper.selectById(coachId);
        if (coach == null) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND, "教练不存在");
        }
        return coach;
    }

    private BigDecimal normalizeCommission(BigDecimal rate) {
        BigDecimal value = rate == null ? DEFAULT_COMMISSION : rate;
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(new BigDecimal("0.9")) > 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "抽成比例应在 0–0.9 之间");
        }
        return value;
    }
}
