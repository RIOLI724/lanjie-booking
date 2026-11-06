package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.dto.HoldRequest;
import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.entity.BookingItem;
import com.lanjie.booking.entity.Court;
import com.lanjie.booking.entity.PayOrder;
import com.lanjie.booking.entity.Slot;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.entity.UserBan;
import com.lanjie.booking.enums.BookingItemType;
import com.lanjie.booking.enums.BookingStatus;
import com.lanjie.booking.enums.BookingType;
import com.lanjie.booking.enums.PayChannel;
import com.lanjie.booking.enums.PayOrderStatus;
import com.lanjie.booking.enums.PayPurpose;
import com.lanjie.booking.enums.SlotStatus;
import com.lanjie.booking.enums.UserStatus;
import com.lanjie.booking.mapper.BookingItemMapper;
import com.lanjie.booking.mapper.BookingMapper;
import com.lanjie.booking.mapper.CourtMapper;
import com.lanjie.booking.mapper.PayOrderMapper;
import com.lanjie.booking.mapper.SlotMapper;
import com.lanjie.booking.mapper.UserBanMapper;
import com.lanjie.booking.mapper.UserMapper;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.vo.BookingVO;
import com.lanjie.booking.vo.CheckoutPreviewVO;
import com.lanjie.booking.vo.CheckinTokenVO;
import com.lanjie.booking.vo.HoldVO;
import com.lanjie.booking.vo.ItemVO;
import com.lanjie.booking.vo.RescheduleVO;
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
 * 订场订单：锁位（Redis 快速失败 + DB 条件更新兜底）→ 支付 → 核销/取消/改期/爽约。
 * 连选约束：同一场地、时间连续、最多 4 格；每天限 2 单场地订单；
 * 改期：开场前 2h、限 1 次、多退少补；取消：≥24h 全退 / 2–24h 退 70% / <2h 不退。
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
    private final PayOrderMapper payOrderMapper;
    private final UserBanMapper userBanMapper;
    private final BookingAuditService audit;
    private final RefundService refundService;
    private final StringRedisTemplate redis;

    public BookingService(UserMapper userMapper, CourtMapper courtMapper, SlotMapper slotMapper,
                          BookingMapper bookingMapper, BookingItemMapper bookingItemMapper,
                          PayOrderMapper payOrderMapper, UserBanMapper userBanMapper,
                          BookingAuditService audit, RefundService refundService, StringRedisTemplate redis) {
        this.userMapper = userMapper;
        this.courtMapper = courtMapper;
        this.slotMapper = slotMapper;
        this.bookingMapper = bookingMapper;
        this.bookingItemMapper = bookingItemMapper;
        this.payOrderMapper = payOrderMapper;
        this.userBanMapper = userBanMapper;
        this.audit = audit;
        this.refundService = refundService;
        this.redis = redis;
    }

    @Transactional
    public HoldVO hold(LoginUser loginUser, HoldRequest request) {
        User user = userMapper.selectById(loginUser.userId());
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_FROZEN);
        }
        checkNotBanned(user.getId());
        List<Long> slotIds = request.getSlotIds().stream().distinct().toList();
        if (slotIds.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请选择时段");
        }
        if (slotIds.size() > MAX_CONTIGUOUS) {
            throw new BusinessException(ErrorCode.SLOT_TOO_MANY);
        }

        List<Slot> slots = loadSlots(slotIds);
        slots.sort(Comparator.comparing(Slot::getStartAt));
        validateContiguous(slots);

        LocalDateTime now = LocalDateTime.now();
        if (slots.get(0).getStartAt().isBefore(now)) {
            throw new BusinessException(ErrorCode.SLOT_IN_PAST);
        }
        LocalDate day = slots.get(0).getSlotDate();
        if (bookingMapper.countActiveCourtBookingsOnDate(user.getId(),
                day.atStartOfDay(), day.plusDays(1).atStartOfDay(), null) >= DAILY_BOOKING_LIMIT) {
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
            booking.setRescheduleCount(0);
            insertWithRetry(booking);

            for (int i = 0; i < slots.size(); i++) {
                bookingItemMapper.insert(toItem(booking.getId(), slots.get(i), itemVOs.get(i).title()));
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
        if (booking.getStatus() == BookingStatus.PENDING_PAYMENT) {
            if (bookingMapper.markCancelled(booking.getId(), "USER_CANCEL") == 0) {
                throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID);
            }
            slotMapper.releaseByBooking(booking.getId());
            audit.write(booking.getId(), BookingStatus.PENDING_PAYMENT.name(),
                    BookingStatus.CANCELLED.name(), "USER", "用户取消");
            return;
        }
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            LocalDateTime firstStart = firstItemStart(booking.getId());
            int refund = refundService.refundForCancel(booking, firstStart, "用户取消");
            if (bookingMapper.markCancelledConfirmed(booking.getId(), "USER_CANCEL") == 0) {
                throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID);
            }
            // 已占格子与新改期占位都释放
            slotMapper.releaseBookedByBooking(booking.getId());
            slotMapper.releaseByBooking(booking.getId());
            audit.write(booking.getId(), BookingStatus.CONFIRMED.name(),
                    BookingStatus.CANCELLED.name(), "USER", "用户取消，退款 " + refund + " 分");
            log.info("booking cancelled with refund: booking={} refund={}", bookingId, refund);
            return;
        }
        throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID);
    }

    /**
     * 改期：开场前 2h、限 1 次。差价 > 0 走补差价支付（先锁新格，支付成功后生效）；
     * 差价 ≤ 0 立即生效并原路退差价。
     */
    @Transactional
    public RescheduleVO reschedule(LoginUser loginUser, Long bookingId, HoldRequest request) {
        Booking booking = ownBooking(loginUser, bookingId);
        if (booking.getType() != BookingType.COURT || booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID);
        }
        if (booking.getRescheduleCount() >= 1) {
            throw new BusinessException(ErrorCode.BOOKING_RESCHEDULE_LIMIT);
        }
        LocalDateTime firstStart = firstItemStart(bookingId);
        if (LocalDateTime.now().isAfter(firstStart.minusHours(2))) {
            throw new BusinessException(ErrorCode.BOOKING_RESCHEDULE_CLOSED);
        }

        List<Long> slotIds = request.getSlotIds().stream().distinct().toList();
        if (slotIds.isEmpty() || slotIds.size() > MAX_CONTIGUOUS) {
            throw new BusinessException(ErrorCode.SLOT_TOO_MANY);
        }
        List<Slot> slots = loadSlots(slotIds);
        slots.sort(Comparator.comparing(Slot::getStartAt));
        validateContiguous(slots);
        if (slots.get(0).getStartAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.SLOT_IN_PAST);
        }
        LocalDate day = slots.get(0).getSlotDate();
        if (bookingMapper.countActiveCourtBookingsOnDate(booking.getUserId(),
                day.atStartOfDay(), day.plusDays(1).atStartOfDay(), booking.getId()) >= DAILY_BOOKING_LIMIT) {
            throw new BusinessException(ErrorCode.BOOKING_DAILY_LIMIT);
        }

        int newListAmount = slots.stream().mapToInt(Slot::getPriceCents).sum();
        int diff = newListAmount - booking.getPaidCents();

        String token = UUID.randomUUID().toString().replace("-", "");
        List<String> fastFailKeys = slotIds.stream().sorted().map(id -> "lanjie:hold:" + id).toList();
        boolean acquired = acquireFastFail(fastFailKeys, token);
        try {
            slotMapper.reclaimExpired(slotIds, LocalDateTime.now());
            int held = slotMapper.holdSlots(slotIds, token, LocalDateTime.now().plus(HOLD_TTL), booking.getId());
            if (held != slotIds.size()) {
                throw new BusinessException(ErrorCode.SLOT_TAKEN);
            }
            List<ItemVO> newItems = slots.stream()
                    .map(s -> new ItemVO("%s %s %s-%s".formatted(courtName(s.getCourtId()),
                            s.getSlotDate().format(ITEM_DATE), s.getStartAt().toLocalTime(),
                            s.getEndAt().toLocalTime()),
                            s.getStartAt().toString(), s.getEndAt().toString(), s.getPriceCents()))
                    .toList();
            if (diff > 0) {
                String payNo = createDiffPayOrder(booking, diff);
                log.info("reschedule pending diff payment: booking={} diff={}", bookingId, diff);
                return new RescheduleVO(bookingId, diff, payNo, newItems);
            }
            finalizeReschedule(bookingId, slots, diff);
            if (diff < 0) {
                refundService.refundForReschedule(booking, -diff, "改期差价退回");
            }
            return new RescheduleVO(bookingId, diff, null, newItems);
        } finally {
            if (acquired) {
                redis.delete(fastFailKeys);
            }
        }
    }

    /** 改期落地：计数 + 差价入实付、旧格释放、新格确认、明细替换。供支付确认回调调用 */
    @Transactional
    public void finalizeReschedule(Long bookingId, List<Slot> newSlots, int diffCents) {
        if (bookingMapper.applyReschedule(bookingId, diffCents) == 0) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID);
        }
        slotMapper.releaseBookedByBooking(bookingId);
        slotMapper.confirmByBooking(bookingId);
        replaceItems(bookingId, newSlots);
        audit.write(bookingId, BookingStatus.CONFIRMED.name(), BookingStatus.CONFIRMED.name(), "USER",
                "改期生效" + (diffCents > 0 ? "，补差价 " + diffCents + " 分"
                        : diffCents < 0 ? "，退差价 " + (-diffCents) + " 分" : ""));
        log.info("reschedule finalized: booking={} diff={}", bookingId, diffCents);
    }

    /** 我的核销码：一次性 token，首次请求生成 */
    @Transactional
    public CheckinTokenVO checkinToken(LoginUser loginUser, Long bookingId) {
        Booking booking = ownBooking(loginUser, bookingId);
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID);
        }
        if (booking.getCheckinToken() == null || booking.getCheckinToken().isBlank()) {
            booking.setCheckinToken(UUID.randomUUID().toString().replace("-", ""));
            bookingMapper.updateById(booking);
        }
        List<BookingItem> items = bookingItemMapper.selectList(new LambdaQueryWrapper<BookingItem>()
                .eq(BookingItem::getBookingId, bookingId)
                .orderByAsc(BookingItem::getStartAt));
        return new CheckinTokenVO(booking.getCheckinToken(),
                items.get(0).getStartAt().toString(), items.get(items.size() - 1).getEndAt().toString());
    }

    /** 前台扫码核销：一次性码 + 时间窗（开场前 30 分钟 ~ 结束后 30 分钟） */
    @Transactional
    public void adminCheckin(Long bookingId, String token) {
        Booking booking = bookingMapper.selectById(bookingId);
        if (booking == null) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND);
        }
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID);
        }
        if (token == null || booking.getCheckinToken() == null || !token.equals(booking.getCheckinToken())) {
            throw new BusinessException(ErrorCode.BOOKING_CHECKIN_TOKEN);
        }
        List<BookingItem> items = bookingItemMapper.selectList(new LambdaQueryWrapper<BookingItem>()
                .eq(BookingItem::getBookingId, bookingId));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = items.get(0).getStartAt().minusMinutes(30);
        LocalDateTime windowEnd = items.get(items.size() - 1).getEndAt().plusMinutes(30);
        if (now.isBefore(windowStart) || now.isAfter(windowEnd)) {
            throw new BusinessException(ErrorCode.BOOKING_CHECKIN_WINDOW);
        }
        if (bookingMapper.markCheckedIn(bookingId, token, now) == 0) {
            throw new BusinessException(ErrorCode.BOOKING_CHECKIN_TOKEN);
        }
        audit.write(bookingId, BookingStatus.CONFIRMED.name(), BookingStatus.CONFIRMED.name(),
                "ADMIN", "现场核销");
    }

    private void checkNotBanned(Long userId) {
        UserBan ban = userBanMapper.selectOne(new LambdaQueryWrapper<UserBan>()
                .eq(UserBan::getUserId, userId)
                .gt(UserBan::getEndAt, LocalDateTime.now())
                .last("LIMIT 1"));
        if (ban != null) {
            throw new BusinessException(ErrorCode.USER_BANNED,
                    "因爽约被暂停预订，" + ban.getEndAt().toLocalDate() + " 恢复");
        }
    }

    /** 改期补差价支付单：支付确认后由 PaymentService 调 finalizeReschedule 落地 */
    private String createDiffPayOrder(Booking booking, int diff) {
        PayOrder payOrder = new PayOrder();
        payOrder.setBookingId(booking.getId());
        payOrder.setBizRef(booking.getId());
        payOrder.setUserId(booking.getUserId());
        payOrder.setPurpose(PayPurpose.RESCHEDULE_DIFF);
        payOrder.setChannel(PayChannel.WECHAT_MOCK);
        payOrder.setAmountCents(diff);
        payOrder.setStatus(PayOrderStatus.PENDING);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                payOrder.setPayNo("PY" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
                payOrderMapper.insert(payOrder);
                return payOrder.getPayNo();
            } catch (DuplicateKeyException e) {
                // 单号冲突重试
            }
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR);
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

    private List<Slot> loadSlots(List<Long> slotIds) {
        List<Slot> slots = slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                .in(Slot::getId, slotIds));
        if (slots.size() != slotIds.size()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "时段不存在");
        }
        return slots;
    }

    private BookingItem toItem(Long bookingId, Slot slot, String title) {
        BookingItem item = new BookingItem();
        item.setBookingId(bookingId);
        item.setItemType(BookingItemType.COURT_SLOT);
        item.setRefId(slot.getId());
        item.setTitle(title);
        item.setStartAt(slot.getStartAt());
        item.setEndAt(slot.getEndAt());
        item.setUnitPriceCents(slot.getPriceCents());
        item.setAmountCents(slot.getPriceCents());
        item.setRefundAmountCents(0);
        return item;
    }

    private String courtName(Long courtId) {
        Court court = courtMapper.selectById(courtId);
        return court != null ? court.getName() : "场地";
    }

    private void replaceItems(Long bookingId, List<Slot> slots) {
        bookingItemMapper.delete(new LambdaQueryWrapper<BookingItem>()
                .eq(BookingItem::getBookingId, bookingId));
        for (Slot slot : slots) {
            String title = "%s %s %s-%s".formatted(courtName(slot.getCourtId()),
                    slot.getSlotDate().format(ITEM_DATE),
                    slot.getStartAt().toLocalTime(), slot.getEndAt().toLocalTime());
            bookingItemMapper.insert(toItem(bookingId, slot, title));
        }
    }

    private LocalDateTime firstItemStart(Long bookingId) {
        BookingItem first = bookingItemMapper.selectOne(new LambdaQueryWrapper<BookingItem>()
                .eq(BookingItem::getBookingId, bookingId)
                .orderByAsc(BookingItem::getStartAt)
                .last("LIMIT 1"));
        if (first == null) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND);
        }
        return first.getStartAt();
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
        LocalDateTime firstStart = items.isEmpty() ? null : items.get(0).getStartAt();
        int refundQuote = booking.getStatus() == BookingStatus.CONFIRMED && firstStart != null
                ? refundService.quoteRefund(booking, firstStart) : 0;
        return new BookingVO(booking.getId(), booking.getBookingNo(), booking.getType().name(),
                booking.getStatus().name(), booking.getListAmountCents(), booking.getPayableCents(),
                booking.getPaidCents(),
                booking.getHoldExpiresAt() == null ? null : booking.getHoldExpiresAt().toString(),
                booking.getCreatedAt().toString(), booking.getRescheduleCount(), refundQuote,
                items.stream()
                        .map(i -> new ItemVO(i.getTitle(), i.getStartAt().toString(),
                                i.getEndAt().toString(), i.getAmountCents()))
                        .toList());
    }
}
