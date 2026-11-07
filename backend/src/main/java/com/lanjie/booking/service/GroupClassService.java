package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.entity.BookingItem;
import com.lanjie.booking.entity.Coach;
import com.lanjie.booking.entity.CoachService;
import com.lanjie.booking.entity.CoachSlot;
import com.lanjie.booking.entity.GroupClass;
import com.lanjie.booking.entity.GroupEnrollment;
import com.lanjie.booking.entity.Slot;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.entity.UserBan;
import com.lanjie.booking.enums.BookingItemType;
import com.lanjie.booking.enums.BookingStatus;
import com.lanjie.booking.enums.BookingType;
import com.lanjie.booking.enums.CoachServiceType;
import com.lanjie.booking.enums.GroupEnrollmentStatus;
import com.lanjie.booking.enums.GroupClassStatus;
import com.lanjie.booking.enums.SlotStatus;
import com.lanjie.booking.enums.UserStatus;
import com.lanjie.booking.mapper.BookingItemMapper;
import com.lanjie.booking.mapper.BookingMapper;
import com.lanjie.booking.mapper.CoachMapper;
import com.lanjie.booking.mapper.CoachServiceMapper;
import com.lanjie.booking.mapper.CoachSlotMapper;
import com.lanjie.booking.mapper.GroupClassMapper;
import com.lanjie.booking.mapper.GroupEnrollmentMapper;
import com.lanjie.booking.mapper.SlotMapper;
import com.lanjie.booking.mapper.UserBanMapper;
import com.lanjie.booking.mapper.UserMapper;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.vo.GroupClassVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 团课：4 人开班、上限 8 人；满员候补；有人退按顺序递补（递补生成 2 小时待支付单）；
 * 开班前 24h 不足 4 人自动取消并全退（调度器）。
 */
@Slf4j
@Service
public class GroupClassService {

    private static final DateTimeFormatter ITEM_DATE = DateTimeFormatter.ofPattern("MM-dd");
    private static final long PROMOTE_HOLD_MINUTES = 120;

    private final GroupClassMapper classMapper;
    private final GroupEnrollmentMapper enrollmentMapper;
    private final BookingMapper bookingMapper;
    private final BookingItemMapper bookingItemMapper;
    private final CoachMapper coachMapper;
    private final CoachServiceMapper coachServiceMapper;
    private final CoachSlotMapper coachSlotMapper;
    private final SlotMapper slotMapper;
    private final UserMapper userMapper;
    private final UserBanMapper userBanMapper;
    private final RefundService refundService;
    private final BookingAuditService audit;
    @org.springframework.context.annotation.Lazy
    private final BookingService bookingService;

    public GroupClassService(GroupClassMapper classMapper, GroupEnrollmentMapper enrollmentMapper,
                             BookingMapper bookingMapper, BookingItemMapper bookingItemMapper,
                             CoachMapper coachMapper, CoachServiceMapper coachServiceMapper,
                             CoachSlotMapper coachSlotMapper, SlotMapper slotMapper,
                             UserMapper userMapper, UserBanMapper userBanMapper,
                             RefundService refundService, BookingAuditService audit,
                             @org.springframework.context.annotation.Lazy BookingService bookingService) {
        this.classMapper = classMapper;
        this.enrollmentMapper = enrollmentMapper;
        this.bookingMapper = bookingMapper;
        this.bookingItemMapper = bookingItemMapper;
        this.coachMapper = coachMapper;
        this.coachServiceMapper = coachServiceMapper;
        this.coachSlotMapper = coachSlotMapper;
        this.slotMapper = slotMapper;
        this.userMapper = userMapper;
        this.userBanMapper = userBanMapper;
        this.refundService = refundService;
        this.audit = audit;
        this.bookingService = bookingService;
    }

    /** 建班（种子/管理端用）：占用教练档期与场地格（owner_type=GROUP_CLASS） */
    @Transactional
    public GroupClass createClass(Long coachId, Long serviceId, LocalDateTime startAt, Long courtId) {
        CoachService service = coachServiceMapper.selectById(serviceId);
        if (service == null || service.getType() != CoachServiceType.GROUP) {
            throw new BusinessException(ErrorCode.COACH_SERVICE_INVALID);
        }
        if (startAt.isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "团课开始时间必须晚于现在");
        }
        CoachSlot coachSlot = coachSlotMapper.selectOne(new LambdaQueryWrapper<CoachSlot>()
                .eq(CoachSlot::getCoachId, coachId)
                .eq(CoachSlot::getStartAt, startAt)
                .last("LIMIT 1"));
        if (coachSlot == null || coachSlot.getStatus() != SlotStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.SLOT_TAKEN, "教练档期不可用");
        }
        LocalDateTime endAt = startAt.plusMinutes(service.getDurationMin());
        Slot courtSlot = null;
        if (service.getIncludesCourt()) {
            courtSlot = slotMapper.selectOne(new LambdaQueryWrapper<Slot>()
                    .eq(Slot::getCourtId, courtId)
                    .eq(Slot::getStartAt, startAt)
                    .last("LIMIT 1"));
            if (courtSlot == null || courtSlot.getStatus() != SlotStatus.AVAILABLE) {
                throw new BusinessException(ErrorCode.SLOT_TAKEN, "场地格不可用");
            }
        }
        coachSlot.setStatus(SlotStatus.BOOKED);
        coachSlot.setOwnerType("GROUP_CLASS");
        coachSlot.setOwnerId(coachSlot.getId());
        coachSlotMapper.updateById(coachSlot);
        Long classCoachSlotId = coachSlot.getId();
        Long classCourtSlotId = null;
        if (courtSlot != null) {
            courtSlot.setStatus(SlotStatus.BOOKED);
            courtSlot.setOwnerType("GROUP_CLASS");
            courtSlot.setOwnerId(coachSlot.getId());
            slotMapper.updateById(courtSlot);
            classCourtSlotId = courtSlot.getId();
        }
        Coach coach = coachMapper.selectById(coachId);
        GroupClass clazz = new GroupClass();
        clazz.setCoachId(coachId);
        clazz.setServiceId(serviceId);
        clazz.setCoachSlotId(classCoachSlotId);
        clazz.setCourtSlotId(classCourtSlotId);
        clazz.setTitle(coach.getDisplayName() + " · " + service.getTitle());
        clazz.setStartAt(startAt);
        clazz.setEndAt(endAt);
        clazz.setMinStudents(service.getMinStudents());
        clazz.setMaxStudents(service.getMaxStudents());
        clazz.setPriceCents(service.getPriceCents());
        clazz.setEnrolledCount(0);
        clazz.setStatus(GroupClassStatus.OPEN.name());
        classMapper.insert(clazz);
        log.info("group class created: id={} coach={} start={}", clazz.getId(), coachId, startAt);
        return clazz;
    }

    public List<GroupClassVO> list(LocalDate date) {
        return classMapper.selectList(new LambdaQueryWrapper<GroupClass>()
                .in(GroupClass::getStatus, GroupClassStatus.OPEN, GroupClassStatus.FULL)
                .apply("DATE(start_at) = {0}", date)
                .orderByAsc(GroupClass::getStartAt))
                .stream().map(this::toVO).toList();
    }

    public GroupClassVO detail(Long classId) {
        return toVO(loadClass(classId));
    }

    /** 报名：有空位 → 建待支付订单占坑；满员 → 候补 */
    @Transactional
    public GroupEnrollment enroll(LoginUser loginUser, Long classId) {
        User user = userMapper.selectById(loginUser.userId());
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_FROZEN);
        }
        UserBan ban = userBanMapper.selectOne(new LambdaQueryWrapper<UserBan>()
                .eq(UserBan::getUserId, user.getId())
                .gt(UserBan::getEndAt, LocalDateTime.now())
                .last("LIMIT 1"));
        if (ban != null) {
            throw new BusinessException(ErrorCode.USER_BANNED,
                    "因爽约被暂停预订，" + ban.getEndAt().toLocalDate() + " 恢复");
        }
        GroupClass clazz = loadClass(classId);
        if (!GroupClassStatus.OPEN.name().equals(clazz.getStatus())
                && !GroupClassStatus.FULL.name().equals(clazz.getStatus())) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "该场次已取消或已结束");
        }
        if (clazz.getStartAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "该场次已开始");
        }
        GroupEnrollment existing = enrollmentMapper.selectOne(new LambdaQueryWrapper<GroupEnrollment>()
                .eq(GroupEnrollment::getClassId, classId)
                .eq(GroupEnrollment::getUserId, user.getId())
                .last("LIMIT 1"));
        if (existing != null && existing.getStatus() != GroupEnrollmentStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "已报名该场次");
        }

        GroupEnrollment enrollment = new GroupEnrollment();
        enrollment.setClassId(classId);
        enrollment.setUserId(user.getId());
        if (existing != null) {
            enrollment.setId(existing.getId());
        }

        int reserved = classMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<GroupClass>()
                        .eq(GroupClass::getId, classId)
                        .apply("enrolled_count < max_students")
                        .setSql("enrolled_count = enrolled_count + 1"));
        if (reserved > 0) {
            Booking booking = createSeatBooking(user, clazz);
            enrollment.setBookingId(booking.getId());
            enrollment.setStatus(GroupEnrollmentStatus.CONFIRMED);
            upsertEnrollment(enrollment);
            GroupClass fresh = loadClass(classId);
            if (fresh.getEnrolledCount() >= fresh.getMaxStudents()) {
                classMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<GroupClass>()
                        .eq(GroupClass::getId, classId)
                        .set(GroupClass::getStatus, GroupClassStatus.FULL.name()));
            }
            log.info("group enroll: class={} user={} booking={}", classId, user.getId(), booking.getId());
        } else {
            enrollment.setBookingId(null);
            enrollment.setStatus(GroupEnrollmentStatus.WAITLIST);
            upsertEnrollment(enrollment);
            log.info("group waitlist: class={} user={}", classId, user.getId());
        }
        return enrollment;
    }

    /** 用户取消报名：候补直接放弃；已确认的走订单取消（24h 规则）并触发递补 */
    @Transactional
    public void cancelByUser(LoginUser loginUser, Long classId) {
        GroupEnrollment enrollment = enrollmentMapper.selectOne(new LambdaQueryWrapper<GroupEnrollment>()
                .eq(GroupEnrollment::getClassId, classId)
                .eq(GroupEnrollment::getUserId, loginUser.userId())
                .last("LIMIT 1"));
        if (enrollment == null || enrollment.getStatus() == GroupEnrollmentStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND, "没有该场次的报名记录");
        }
        if (enrollment.getStatus() == GroupEnrollmentStatus.WAITLIST) {
            enrollment.setStatus(GroupEnrollmentStatus.CANCELLED);
            enrollmentMapper.updateById(enrollment);
            return;
        }
        bookingService.cancel(loginUser, enrollment.getBookingId());
    }

    /** 取消报名（用户取消团课订单时回调）：退席位并按顺序递补候补 */
    @Transactional
    public void onSeatReleased(Long classId, Long userId) {
        GroupEnrollment enrollment = enrollmentMapper.selectOne(new LambdaQueryWrapper<GroupEnrollment>()
                .eq(GroupEnrollment::getClassId, classId)
                .eq(GroupEnrollment::getUserId, userId)
                .last("LIMIT 1"));
        if (enrollment == null || enrollment.getStatus() != GroupEnrollmentStatus.CONFIRMED) {
            return;
        }
        enrollment.setStatus(GroupEnrollmentStatus.CANCELLED);
        enrollmentMapper.updateById(enrollment);
        classMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<GroupClass>()
                .eq(GroupClass::getId, classId)
                .setSql("enrolled_count = enrolled_count - 1"));
        promoteNext(classId);
        GroupClass clazz = loadClass(classId);
        if (GroupClassStatus.FULL.name().equals(clazz.getStatus())
                && clazz.getEnrolledCount() < clazz.getMaxStudents()) {
            classMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<GroupClass>()
                    .eq(GroupClass::getId, classId)
                    .set(GroupClass::getStatus, GroupClassStatus.OPEN.name()));
        }
    }

    /** 调度器：① 递补单超时作废 ② 开班前 24h 不足 4 人自动取消全退 ③ 结束转已完成 */
    @Transactional
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        // ① 递补单超时：候补的 PENDING 订单过期 → 席位让出（已由 ReleaseScheduler 取消订单）
        List<GroupEnrollment> stale = enrollmentMapper.selectList(new LambdaQueryWrapper<GroupEnrollment>()
                .eq(GroupEnrollment::getStatus, GroupEnrollmentStatus.CONFIRMED)
                .isNotNull(GroupEnrollment::getBookingId));
        for (GroupEnrollment enrollment : stale) {
            Booking booking = bookingMapper.selectById(enrollment.getBookingId());
            if (booking != null && booking.getStatus() == BookingStatus.CANCELLED) {
                enrollment.setStatus(GroupEnrollmentStatus.CANCELLED);
                enrollmentMapper.updateById(enrollment);
                classMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<GroupClass>()
                        .eq(GroupClass::getId, enrollment.getClassId())
                        .setSql("enrolled_count = enrolled_count - 1"));
                promoteNext(enrollment.getClassId());
            }
        }
        // ② 不成班自动取消
        List<GroupClass> starting = classMapper.selectList(new LambdaQueryWrapper<GroupClass>()
                .in(GroupClass::getStatus, GroupClassStatus.OPEN, GroupClassStatus.FULL)
                .between(GroupClass::getStartAt, now, now.plusHours(24)));
        for (GroupClass clazz : starting) {
            long valid = validPaidCount(clazz.getId());
            if (valid < clazz.getMinStudents()) {
                cancelClass(clazz, "开班前 24h 不足 " + clazz.getMinStudents() + " 人，自动取消");
            }
        }
        // ③ 结束转完成
        List<GroupClass> ended = classMapper.selectList(new LambdaQueryWrapper<GroupClass>()
                .in(GroupClass::getStatus, GroupClassStatus.OPEN, GroupClassStatus.FULL)
                .lt(GroupClass::getEndAt, now));
        for (GroupClass clazz : ended) {
            classMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<GroupClass>()
                    .eq(GroupClass::getId, clazz.getId())
                    .set(GroupClass::getStatus, GroupClassStatus.COMPLETED.name()));
        }
    }

    /** 场馆/教练原因取消：全员全退，释放档期与场地 */
    @Transactional
    public void cancelClass(GroupClass clazz, String reason) {
        String fromStatus = clazz.getStatus();
        List<GroupEnrollment> enrollments = enrollmentMapper.selectList(new LambdaQueryWrapper<GroupEnrollment>()
                .eq(GroupEnrollment::getClassId, clazz.getId())
                .eq(GroupEnrollment::getStatus, GroupEnrollmentStatus.CONFIRMED));
        for (GroupEnrollment enrollment : enrollments) {
            Booking booking = bookingMapper.selectById(enrollment.getBookingId());
            if (booking != null && booking.getStatus() == BookingStatus.CONFIRMED) {
                if (bookingMapper.markCancelledConfirmed(booking.getId(), "CLASS_CANCELLED") > 0) {
                    refundService.refundFull(booking, reason);
                }
            } else if (booking != null && booking.getStatus() == BookingStatus.PENDING_PAYMENT) {
                bookingMapper.markCancelled(booking.getId(), "CLASS_CANCELLED");
            }
            enrollment.setStatus(GroupEnrollmentStatus.CANCELLED);
            enrollmentMapper.updateById(enrollment);
        }
        // 候补一并取消，避免班级取消后仍有人等待递补
        enrollmentMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<GroupEnrollment>()
                .eq(GroupEnrollment::getClassId, clazz.getId())
                .eq(GroupEnrollment::getStatus, GroupEnrollmentStatus.WAITLIST)
                .set(GroupEnrollment::getStatus, GroupEnrollmentStatus.CANCELLED));
        clazz.setEnrolledCount(0);
        clazz.setStatus(GroupClassStatus.CANCELLED.name());
        classMapper.updateById(clazz);
        releaseClassResources(clazz);
        audit.write(clazz.getId(), fromStatus, GroupClassStatus.CANCELLED.name(), "SYSTEM", reason);
        log.warn("group class cancelled: id={} reason={}", clazz.getId(), reason);
    }

    private void promoteNext(Long classId) {
        GroupClass clazz = loadClass(classId);
        if (clazz.getEnrolledCount() >= clazz.getMaxStudents()) {
            return;
        }
        List<GroupEnrollment> waitlist = enrollmentMapper.selectList(new LambdaQueryWrapper<GroupEnrollment>()
                .eq(GroupEnrollment::getClassId, classId)
                .eq(GroupEnrollment::getStatus, GroupEnrollmentStatus.WAITLIST)
                .orderByAsc(GroupEnrollment::getCreatedAt)
                .last("LIMIT 1"));
        if (waitlist.isEmpty()) {
            return;
        }
        GroupEnrollment next = waitlist.get(0);
        User user = userMapper.selectById(next.getUserId());
        Booking booking = createSeatBooking(user, clazz);
        next.setBookingId(booking.getId());
        next.setStatus(GroupEnrollmentStatus.CONFIRMED);
        enrollmentMapper.updateById(next);
        classMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<GroupClass>()
                .eq(GroupClass::getId, classId)
                .setSql("enrolled_count = enrolled_count + 1"));
        log.info("waitlist promoted: class={} user={} booking={}", classId, next.getUserId(), booking.getId());
    }

    private Booking createSeatBooking(User user, GroupClass clazz) {
        Booking booking = new Booking();
        booking.setUserId(user.getId());
        booking.setVenueId(defaultVenueId());
        booking.setType(BookingType.GROUP);
        booking.setStatus(BookingStatus.PENDING_PAYMENT);
        booking.setHoldExpiresAt(LocalDateTime.now().plusMinutes(PROMOTE_HOLD_MINUTES));
        booking.setListAmountCents(clazz.getPriceCents());
        booking.setDiscountCents(0);
        booking.setPayableCents(clazz.getPriceCents());
        booking.setPaidCents(0);
        booking.setRescheduleCount(0);
        booking.setBookingNo("BJ" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
        bookingMapper.insert(booking);
        String title = clazz.getTitle() + " " + clazz.getStartAt().format(ITEM_DATE)
                + " " + clazz.getStartAt().toLocalTime() + "-" + clazz.getEndAt().toLocalTime();
        BookingItem item = new BookingItem();
        item.setBookingId(booking.getId());
        item.setItemType(BookingItemType.GROUP_SEAT);
        item.setRefId(clazz.getId());
        item.setTitle(title);
        item.setStartAt(clazz.getStartAt());
        item.setEndAt(clazz.getEndAt());
        item.setUnitPriceCents(clazz.getPriceCents());
        item.setAmountCents(clazz.getPriceCents());
        item.setRefundAmountCents(0);
        bookingItemMapper.insert(item);
        return booking;
    }

    private void releaseClassResources(GroupClass clazz) {
        if (clazz.getCoachSlotId() != null) {
            CoachSlot slot = coachSlotMapper.selectById(clazz.getCoachSlotId());
            if (slot != null && "GROUP_CLASS".equals(slot.getOwnerType())) {
                slot.setStatus(SlotStatus.AVAILABLE);
                slot.setOwnerType(null);
                slot.setOwnerId(null);
                coachSlotMapper.updateById(slot);
            }
        }
        if (clazz.getCourtSlotId() != null) {
            Slot slot = slotMapper.selectById(clazz.getCourtSlotId());
            if (slot != null && "GROUP_CLASS".equals(slot.getOwnerType())) {
                slot.setStatus(SlotStatus.AVAILABLE);
                slot.setOwnerType(null);
                slot.setOwnerId(null);
                slotMapper.updateById(slot);
            }
        }
    }

    private long validPaidCount(Long classId) {
        return enrollmentMapper.selectCount(new LambdaQueryWrapper<GroupEnrollment>()
                .eq(GroupEnrollment::getClassId, classId)
                .eq(GroupEnrollment::getStatus, GroupEnrollmentStatus.CONFIRMED));
    }

    private void upsertEnrollment(GroupEnrollment enrollment) {
        if (enrollment.getId() != null) {
            enrollmentMapper.updateById(enrollment);
        } else {
            enrollmentMapper.insert(enrollment);
        }
    }

    private Long defaultVenueId() {
        return 1L;
    }

    private GroupClass loadClass(Long classId) {
        GroupClass clazz = classMapper.selectById(classId);
        if (clazz == null) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND, "团课场次不存在");
        }
        return clazz;
    }

    private GroupClassVO toVO(GroupClass clazz) {
        Coach coach = coachMapper.selectById(clazz.getCoachId());
        return new GroupClassVO(clazz.getId(), clazz.getTitle(),
                coach != null ? coach.getDisplayName() : "",
                clazz.getStartAt().toString(), clazz.getEndAt().toString(),
                clazz.getMinStudents(), clazz.getMaxStudents(),
                clazz.getPriceCents(), clazz.getEnrolledCount(), clazz.getStatus());
    }
}
