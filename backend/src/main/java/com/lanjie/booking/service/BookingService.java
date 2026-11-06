package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.dto.HoldRequest;
import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.entity.BookingItem;
import com.lanjie.booking.entity.Court;
import com.lanjie.booking.entity.Slot;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.enums.BookingItemType;
import com.lanjie.booking.enums.BookingStatus;
import com.lanjie.booking.enums.BookingType;
import com.lanjie.booking.enums.UserStatus;
import com.lanjie.booking.mapper.BookingItemMapper;
import com.lanjie.booking.mapper.BookingMapper;
import com.lanjie.booking.mapper.CourtMapper;
import com.lanjie.booking.mapper.SlotMapper;
import com.lanjie.booking.mapper.UserMapper;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.vo.BookingVO;
import com.lanjie.booking.vo.CheckoutPreviewVO;
import com.lanjie.booking.vo.HoldVO;
import com.lanjie.booking.vo.ItemVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订场订单：锁位（Redis 快速失败 + DB 条件更新兜底）→ 支付 → 状态机。
 * 连选约束：同一场地、时间连续、最多 4 格；每天限 2 单场地订单。
 */
@Slf4j
@Service
public class BookingService {

    private static final Duration HOLD_TTL = Duration.ofMinutes(15);
    private static final int MAX_CONTIGUOUS = 4;
    private static final int DAILY_BOOKING_LIMIT = 2;
    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter ITEM_DATE = DateTimeFormatter.ofPattern("MM-dd");

    private final UserMapper userMapper;
    private final CourtMapper courtMapper;
    private final SlotMapper slotMapper;
    private final BookingMapper bookingMapper;
    private final BookingItemMapper bookingItemMapper;
    private final BookingAuditService audit;
    private final StringRedisTemplate redis;

    public BookingService(UserMapper userMapper, CourtMapper courtMapper, SlotMapper slotMapper,
                          BookingMapper bookingMapper, BookingItemMapper bookingItemMapper,
                          BookingAuditService audit, StringRedisTemplate redis) {
        this.userMapper = userMapper;
        this.courtMapper = courtMapper;
        this.slotMapper = slotMapper;
        this.bookingMapper = bookingMapper;
        this.bookingItemMapper = bookingItemMapper;
        this.audit = audit;
        this.redis = redis;
    }

    @Transactional
    public HoldVO hold(LoginUser loginUser, HoldRequest request) {
        User user = userMapper.selectById(loginUser.userId());
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_FROZEN);
        }
        List<Long> slotIds = request.getSlotIds().stream().distinct().toList();
        if (slotIds.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请选择时段");
        }
        if (slotIds.size() > MAX_CONTIGUOUS) {
            throw new BusinessException(ErrorCode.SLOT_TOO_MANY);
        }

        List<Slot> slots = slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                .in(Slot::getId, slotIds));
        if (slots.size() != slotIds.size()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "时段不存在");
        }
        slots.sort(Comparator.comparing(Slot::getStartAt));
        validateContiguous(slots);

        LocalDateTime now = LocalDateTime.now();
        if (slots.get(0).getStartAt().isBefore(now)) {
            throw new BusinessException(ErrorCode.SLOT_IN_PAST);
        }
        LocalDate day = slots.get(0).getSlotDate();
        if (bookingMapper.countActiveCourtBookingsOnDate(user.getId(),
                day.atStartOfDay(), day.plusDays(1).atStartOfDay()) >= DAILY_BOOKING_LIMIT) {
            throw new BusinessException(ErrorCode.BOOKING_DAILY_LIMIT);
        }

        Court court = courtMapper.selectById(slots.get(0).getCourtId());
        String token = UUID.randomUUID().toString().replace("-", "");
        List<String> fastFailKeys = slotIds.stream().sorted().map(id -> "lanjie:hold:" + id).toList();
        boolean acquired = acquireFastFail(fastFailKeys, token);
        try {
            // 过期 HELD 就地回收，不等扫库兜底
            slotMapper.reclaimExpired(slotIds, now);

            List<ItemVO> itemVOs = new ArrayList<>();
            int listAmount = 0;
            String courtName = court != null ? court.getName() : "场地";
            for (Slot slot : slots) {
                String title = "%s %s %s-%s".formatted(courtName,
                        slot.getSlotDate().format(ITEM_DATE),
                        slot.getStartAt().toLocalTime(), slot.getEndAt().toLocalTime());
                itemVOs.add(new ItemVO(title, slot.getStartAt().toString(),
                        slot.getEndAt().toString(), slot.getPriceCents()));
                listAmount += slot.getPriceCents();
            }

            Booking booking = new Booking();
            booking.setUserId(user.getId());
            booking.setVenueId(slots.get(0).getVenueId());
            booking.setType(BookingType.COURT);
            booking.setStatus(BookingStatus.PENDING_PAYMENT);
            booking.setContactName(request.getContactName());
            booking.setContactPhone(request.getContactPhone());
            booking.setHoldExpiresAt(now.plus(HOLD_TTL));
            booking.setListAmountCents(listAmount);
            booking.setDiscountCents(0);
            booking.setPayableCents(listAmount);
            booking.setPaidCents(0);
            insertWithRetry(booking);

            for (int i = 0; i < slots.size(); i++) {
                Slot slot = slots.get(i);
                BookingItem item = new BookingItem();
                item.setBookingId(booking.getId());
                item.setItemType(BookingItemType.COURT_SLOT);
                item.setRefId(slot.getId());
                item.setTitle(itemVOs.get(i).title());
                item.setStartAt(slot.getStartAt());
                item.setEndAt(slot.getEndAt());
                item.setUnitPriceCents(slot.getPriceCents());
                item.setAmountCents(slot.getPriceCents());
                item.setRefundAmountCents(0);
                bookingItemMapper.insert(item);
            }

            int held = slotMapper.holdSlots(slotIds, token, booking.getHoldExpiresAt(), booking.getId());
            if (held != slotIds.size()) {
                throw new BusinessException(ErrorCode.SLOT_TAKEN);
            }
            audit.write(booking.getId(), null, BookingStatus.PENDING_PAYMENT.name(), "USER", "锁位");
            log.info("booking held: bookingNo={} slots={} user={}", booking.getBookingNo(), slotIds, user.getId());
            return new HoldVO(booking.getId(), booking.getBookingNo(), booking.getPayableCents(),
                    booking.getHoldExpiresAt().toString(), itemVOs);
        } finally {
            if (acquired) {
                redis.delete(fastFailKeys);
            }
        }
    }

    public List<BookingVO> list(LoginUser loginUser, BookingStatus status) {
        LambdaQueryWrapper<Booking> query = new LambdaQueryWrapper<Booking>()
                .eq(Booking::getUserId, loginUser.userId())
                .orderByDesc(Booking::getCreatedAt)
                .last("LIMIT 50");
        if (status != null) {
            query.eq(Booking::getStatus, status);
        }
        List<Booking> bookings = bookingMapper.selectList(query);
        Map<Long, List<BookingItem>> itemsByBooking = itemsOf(bookings);
        return bookings.stream()
                .map(b -> toVO(b, itemsByBooking.getOrDefault(b.getId(), List.of())))
                .toList();
    }

    public BookingVO detail(LoginUser loginUser, Long bookingId) {
        Booking booking = ownBooking(loginUser, bookingId);
        List<BookingItem> items = bookingItemMapper.selectList(new LambdaQueryWrapper<BookingItem>()
                .eq(BookingItem::getBookingId, bookingId)
                .orderByAsc(BookingItem::getStartAt));
        return toVO(booking, items);
    }

    @Transactional
    public CheckoutPreviewVO checkoutPreview(LoginUser loginUser, Long bookingId) {
        Booking booking = ownBooking(loginUser, bookingId);
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID);
        }
        return new CheckoutPreviewVO(booking.getListAmountCents(), booking.getDiscountCents(), booking.getPayableCents());
    }

    @Transactional
    public void cancel(LoginUser loginUser, Long bookingId) {
        Booking booking = ownBooking(loginUser, bookingId);
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return;
        }
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new BusinessException(ErrorCode.BOOKING_CANCEL_UNSUPPORTED);
        }
        if (bookingMapper.markCancelled(booking.getId(), "USER_CANCEL") == 0) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID);
        }
        slotMapper.releaseByBooking(booking.getId());
        audit.write(booking.getId(), BookingStatus.PENDING_PAYMENT.name(),
                BookingStatus.CANCELLED.name(), "USER", "用户取消");
    }

    private void validateContiguous(List<Slot> slots) {
        Long courtId = slots.get(0).getCourtId();
        Long venueId = slots.get(0).getVenueId();
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (!slot.getCourtId().equals(courtId) || !slot.getVenueId().equals(venueId)) {
                throw new BusinessException(ErrorCode.SLOT_NOT_CONTIGUOUS);
            }
            if (i > 0 && !slot.getStartAt().equals(slots.get(i - 1).getStartAt().plusHours(1))) {
                throw new BusinessException(ErrorCode.SLOT_NOT_CONTIGUOUS);
            }
        }
    }

    /** Redis 快速失败：挂掉就退化成纯 DB 条件更新，不影响正确性 */
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
            log.warn("redis fast-fail unavailable, fall back to db conditional update: {}", e.getMessage());
            return false;
        }
    }

    private void insertWithRetry(Booking booking) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (booking.getBookingNo() == null) {
                    booking.setBookingNo(newBookingNo());
                }
                bookingMapper.insert(booking);
                return;
            } catch (DuplicateKeyException e) {
                booking.setBookingNo(null);
            }
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR);
    }

    private String newBookingNo() {
        return "BJ" + LocalDate.now().format(NO_DATE)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private Booking ownBooking(LoginUser loginUser, Long bookingId) {
        Booking booking = bookingMapper.selectById(bookingId);
        if (booking == null || !booking.getUserId().equals(loginUser.userId())) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND);
        }
        return booking;
    }

    private Map<Long, List<BookingItem>> itemsOf(List<Booking> bookings) {
        if (bookings.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = bookings.stream().map(Booking::getId).toList();
        return bookingItemMapper.selectList(new LambdaQueryWrapper<BookingItem>()
                        .in(BookingItem::getBookingId, ids)
                        .orderByAsc(BookingItem::getStartAt))
                .stream()
                .collect(Collectors.groupingBy(BookingItem::getBookingId));
    }

    private BookingVO toVO(Booking booking, List<BookingItem> items) {
        return new BookingVO(booking.getId(), booking.getBookingNo(), booking.getType().name(),
                booking.getStatus().name(), booking.getListAmountCents(), booking.getPayableCents(),
                booking.getPaidCents(),
                booking.getHoldExpiresAt() == null ? null : booking.getHoldExpiresAt().toString(),
                booking.getCreatedAt().toString(),
                items.stream()
                        .map(i -> new ItemVO(i.getTitle(), i.getStartAt().toString(),
                                i.getEndAt().toString(), i.getAmountCents()))
                        .toList());
    }
}
