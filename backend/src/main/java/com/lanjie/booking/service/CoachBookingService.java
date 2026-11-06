package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.dto.CoachHoldRequest;
import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.entity.BookingItem;
import com.lanjie.booking.entity.Coach;
import com.lanjie.booking.entity.CoachPackage;
import com.lanjie.booking.entity.CoachService;
import com.lanjie.booking.entity.CoachSlot;
import com.lanjie.booking.entity.Slot;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.entity.UserBan;
import com.lanjie.booking.enums.BookingItemType;
import com.lanjie.booking.enums.BookingStatus;
import com.lanjie.booking.enums.BookingType;
import com.lanjie.booking.enums.CoachServiceType;
import com.lanjie.booking.enums.PackageStatus;
import com.lanjie.booking.enums.SlotStatus;
import com.lanjie.booking.enums.UserStatus;
import com.lanjie.booking.mapper.CoachMapper;
import com.lanjie.booking.mapper.CoachPackageMapper;
import com.lanjie.booking.mapper.CoachServiceMapper;
import com.lanjie.booking.mapper.CoachSlotMapper;
import com.lanjie.booking.mapper.SlotMapper;
import com.lanjie.booking.mapper.UserBanMapper;
import com.lanjie.booking.mapper.UserMapper;
import com.lanjie.booking.mapper.VenueMapper;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.vo.HoldVO;
import com.lanjie.booking.vo.ItemVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 私教预约：教练档期 + 场地格双资源原子锁定（含场地时），课包核销 0 元确认。
 * 取消规则：开场前 ≥24h 全退/退课时，<24h 不退/扣课时（教练课用 24h 硬边界，不用场地阶梯）。
 */
@Slf4j
@Service
public class CoachBookingService {

    private static final Duration HOLD_TTL = Duration.ofMinutes(15);
    private static final DateTimeFormatter ITEM_DATE = DateTimeFormatter.ofPattern("MM-dd");

    private final UserMapper userMapper;
    private final UserBanMapper userBanMapper;
    private final CoachMapper coachMapper;
    private final CoachServiceMapper coachServiceMapper;
    private final CoachSlotMapper coachSlotMapper;
    private final CoachPackageMapper coachPackageMapper;
    private final SlotMapper slotMapper;
    private final com.lanjie.booking.mapper.CourtMapper courtMapper;
    private final VenueMapper venueMapper;
    private final com.lanjie.booking.mapper.BookingMapper bookingMapper;
    private final com.lanjie.booking.mapper.BookingItemMapper bookingItemMapper;
    private final BookingAuditService audit;
    private final StringRedisTemplate redis;

    public CoachBookingService(UserMapper userMapper, UserBanMapper userBanMapper,
                               CoachMapper coachMapper, CoachServiceMapper coachServiceMapper,
                               CoachSlotMapper coachSlotMapper, CoachPackageMapper coachPackageMapper,
                               SlotMapper slotMapper, com.lanjie.booking.mapper.CourtMapper courtMapper,
                               VenueMapper venueMapper,
                               com.lanjie.booking.mapper.BookingMapper bookingMapper,
                               com.lanjie.booking.mapper.BookingItemMapper bookingItemMapper,
                               BookingAuditService audit, StringRedisTemplate redis) {
        this.userMapper = userMapper;
        this.userBanMapper = userBanMapper;
        this.coachMapper = coachMapper;
        this.coachServiceMapper = coachServiceMapper;
        this.coachSlotMapper = coachSlotMapper;
        this.coachPackageMapper = coachPackageMapper;
        this.slotMapper = slotMapper;
        this.courtMapper = courtMapper;
        this.venueMapper = venueMapper;
        this.bookingMapper = bookingMapper;
        this.bookingItemMapper = bookingItemMapper;
        this.audit = audit;
        this.redis = redis;
    }

    @Transactional
    public HoldVO hold(LoginUser loginUser, CoachHoldRequest request) {
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

        CoachSlot coachSlot = coachSlotMapper.selectById(request.getCoachSlotId());
        if (coachSlot == null || coachSlot.getStatus() != SlotStatus.AVAILABLE
                || coachSlot.getStartAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.SLOT_TAKEN, "教练档期不可预订");
        }
        Coach coach = coachMapper.selectById(coachSlot.getCoachId());
        CoachService service = coachServiceMapper.selectById(request.getServiceId());
        if (coach == null || service == null || !"ACTIVE".equals(service.getStatus())
                || !service.getCoachId().equals(coachSlot.getCoachId())
                || service.getType() != CoachServiceType.PRIVATE) {
            throw new BusinessException(ErrorCode.COACH_SERVICE_INVALID);
        }

        CoachPackage pkg = null;
        if (request.getPackageId() != null) {
            pkg = coachPackageMapper.selectById(request.getPackageId());
            if (pkg == null || !pkg.getUserId().equals(user.getId())
                    || !pkg.getCoachId().equals(coach.getId())
                    || pkg.getStatus() != PackageStatus.ACTIVE
                    || pkg.getRemainingSessions() <= 0
                    || pkg.getExpiresAt().isBefore(LocalDateTime.now())) {
                throw new BusinessException(ErrorCode.PACKAGE_UNAVAILABLE);
            }
        }

        // 含场地：场地格与教练档期同一时段，双资源一起锁
        Slot courtSlot = null;
        if (service.getIncludesCourt()) {
            if (request.getCourtSlotId() == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "该服务含场地，请同时选择场地时段");
            }
            courtSlot = slotMapper.selectById(request.getCourtSlotId());
            if (courtSlot == null || courtSlot.getStatus() != SlotStatus.AVAILABLE
                    || !courtSlot.getStartAt().equals(coachSlot.getStartAt())
                    || courtSlot.getStartAt().isBefore(LocalDateTime.now())) {
                throw new BusinessException(ErrorCode.SLOT_TAKEN, "场地时段不可预订");
            }
        }

        int payable = pkg != null ? 0 : service.getPriceCents();
        String token = UUID.randomUUID().toString().replace("-", "");
        List<Long> allSlotIds = new ArrayList<>();
        allSlotIds.add(coachSlot.getId());
        if (courtSlot != null) {
            allSlotIds.add(courtSlot.getId());
        }
        List<String> fastFailKeys = allSlotIds.stream().map(id -> "lanjie:hold:" + id).toList();
        boolean acquired = acquireFastFail(fastFailKeys, token);
        try {
            coachSlotMapper.reclaimExpired(List.of(coachSlot.getId()), LocalDateTime.now());
            if (courtSlot != null) {
                slotMapper.reclaimExpired(List.of(courtSlot.getId()), LocalDateTime.now());
            }

            Booking booking = new Booking();
            booking.setUserId(user.getId());
            booking.setVenueId(courtSlot != null ? courtSlot.getVenueId() : defaultVenueId());
            booking.setType(BookingType.COACH);
            booking.setStatus(BookingStatus.PENDING_PAYMENT);
            booking.setContactName(request.getContactName());
            booking.setContactPhone(request.getContactPhone());
            booking.setHoldExpiresAt(LocalDateTime.now().plus(HOLD_TTL));
            booking.setListAmountCents(payable);
            booking.setDiscountCents(0);
            booking.setPayableCents(payable);
            booking.setPaidCents(0);
            booking.setRescheduleCount(0);
            booking.setPackageId(pkg != null ? pkg.getId() : null);
            insertWithRetry(booking);

            String coachTitle = "%s 私教 %s %s-%s".formatted(coach.getDisplayName(),
                    coachSlot.getStartAt().format(ITEM_DATE),
                    coachSlot.getStartAt().toLocalTime(), coachSlot.getEndAt().toLocalTime());
            List<ItemVO> itemVOs = new ArrayList<>();
            itemVOs.add(new ItemVO(coachTitle, coachSlot.getStartAt().toString(),
                    coachSlot.getEndAt().toString(), payable));
            bookingItemMapper.insert(item(booking.getId(), BookingItemType.COACH_SESSION,
                    coachSlot.getId(), coachTitle, coachSlot, payable));
            if (courtSlot != null) {
                String courtTitle = "%s 场地 %s %s-%s".formatted(courtName(courtSlot.getCourtId()),
                        courtSlot.getStartAt().format(ITEM_DATE),
                        courtSlot.getStartAt().toLocalTime(), courtSlot.getEndAt().toLocalTime());
                itemVOs.add(new ItemVO(courtTitle, courtSlot.getStartAt().toString(),
                        courtSlot.getEndAt().toString(), 0));
                bookingItemMapper.insert(item(booking.getId(), BookingItemType.COURT_SLOT,
                        courtSlot.getId(), courtTitle, courtSlot, 0));
            }

            int coachHeld = coachSlotMapper.holdSlots(List.of(coachSlot.getId()), token,
                    booking.getHoldExpiresAt(), booking.getId());
            int courtHeld = courtSlot == null ? 1
                    : slotMapper.holdSlots(List.of(courtSlot.getId()), token,
                    booking.getHoldExpiresAt(), booking.getId());
            if (coachHeld != 1 || courtHeld != 1) {
                throw new BusinessException(ErrorCode.SLOT_TAKEN);
            }
            audit.write(booking.getId(), null, BookingStatus.PENDING_PAYMENT.name(), "USER", "锁私教位");
            log.info("coach booking held: bookingNo={} coachSlot={} courtSlot={}",
                    booking.getBookingNo(), coachSlot.getId(), courtSlot == null ? null : courtSlot.getId());
            return new HoldVO(booking.getId(), booking.getBookingNo(), payable,
                    booking.getHoldExpiresAt().toString(), itemVOs);
        } finally {
            if (acquired) {
                redis.delete(fastFailKeys);
            }
        }
    }

    private BookingItem item(Long bookingId, BookingItemType type, Long refId, String title,
                             com.lanjie.booking.entity.CoachSlot slot, int amount) {
        return buildItem(bookingId, type, refId, title, slot.getStartAt(), slot.getEndAt(), amount);
    }

    private BookingItem item(Long bookingId, BookingItemType type, Long refId, String title,
                             Slot slot, int amount) {
        return buildItem(bookingId, type, refId, title, slot.getStartAt(), slot.getEndAt(), amount);
    }

    private BookingItem buildItem(Long bookingId, BookingItemType type, Long refId, String title,
                                  LocalDateTime startAt, LocalDateTime endAt, int amount) {
        BookingItem item = new BookingItem();
        item.setBookingId(bookingId);
        item.setItemType(type);
        item.setRefId(refId);
        item.setTitle(title);
        item.setStartAt(startAt);
        item.setEndAt(endAt);
        item.setUnitPriceCents(amount);
        item.setAmountCents(amount);
        item.setRefundAmountCents(0);
        return item;
    }

    private String courtName(Long courtId) {
        com.lanjie.booking.entity.Court court = courtMapper.selectById(courtId);
        return court != null ? court.getName() : "场地";
    }

    private Long defaultVenueId() {
        var venue = venueMapper.selectOne(new LambdaQueryWrapper<com.lanjie.booking.entity.Venue>()
                .eq(com.lanjie.booking.entity.Venue::getStatus,
                        com.lanjie.booking.enums.VenueStatus.ACTIVE)
                .last("LIMIT 1"));
        return venue != null ? venue.getId() : 1L;
    }

    private boolean acquireFastFail(List<String> keys, String token) {
        List<String> acquired = new ArrayList<>();
        try {
            for (String key : keys) {
                Boolean ok = redis.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(15));
                if (ok == null || !ok) {
                    redis.delete(acquired);
                    throw new BusinessException(ErrorCode.SLOT_TAKEN);
                }
                acquired.add(key);
            }
            return true;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("redis fast-fail unavailable: {}", e.getMessage());
            return false;
        }
    }

    private void insertWithRetry(Booking booking) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (booking.getBookingNo() == null) {
                    booking.setBookingNo("BJ" + LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
                }
                bookingMapper.insert(booking);
                return;
            } catch (DuplicateKeyException e) {
                booking.setBookingNo(null);
            }
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR);
    }
}
