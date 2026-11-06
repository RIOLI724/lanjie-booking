package com.lanjie.booking.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.common.Result;
import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.entity.BookingItem;
import com.lanjie.booking.entity.BookingStatusHistory;
import com.lanjie.booking.entity.PayOrder;
import com.lanjie.booking.entity.RefundOrder;
import com.lanjie.booking.entity.Slot;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.enums.BookingStatus;
import com.lanjie.booking.enums.BookingType;
import com.lanjie.booking.enums.SlotStatus;
import com.lanjie.booking.mapper.BookingItemMapper;
import com.lanjie.booking.mapper.BookingMapper;
import com.lanjie.booking.mapper.BookingStatusHistoryMapper;
import com.lanjie.booking.mapper.PayOrderMapper;
import com.lanjie.booking.mapper.RefundOrderMapper;
import com.lanjie.booking.mapper.SlotMapper;
import com.lanjie.booking.mapper.UserMapper;
import com.lanjie.booking.service.BookingAuditService;
import com.lanjie.booking.service.RefundService;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 前台运营操作（P0）：排期可动、订单可处置。
 * 所有敏感操作写订单状态历史；关场/强制取消前必须由前台确认。
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminOpsController {

    private final BookingMapper bookingMapper;
    private final BookingItemMapper bookingItemMapper;
    private final BookingStatusHistoryMapper historyMapper;
    private final PayOrderMapper payOrderMapper;
    private final RefundOrderMapper refundOrderMapper;
    private final SlotMapper slotMapper;
    private final UserMapper userMapper;
    private final RefundService refundService;
    private final BookingAuditService audit;

    public AdminOpsController(BookingMapper bookingMapper, BookingItemMapper bookingItemMapper,
                              BookingStatusHistoryMapper historyMapper, PayOrderMapper payOrderMapper,
                              RefundOrderMapper refundOrderMapper, SlotMapper slotMapper,
                              UserMapper userMapper, RefundService refundService, BookingAuditService audit) {
        this.bookingMapper = bookingMapper;
        this.bookingItemMapper = bookingItemMapper;
        this.historyMapper = historyMapper;
        this.payOrderMapper = payOrderMapper;
        this.refundOrderMapper = refundOrderMapper;
        this.slotMapper = slotMapper;
        this.userMapper = userMapper;
        this.refundService = refundService;
        this.audit = audit;
    }

    /** 场地订单列表：按日期（默认今天）或状态筛选，前台一眼看清今天要处理谁 */
    @GetMapping("/orders")
    public Result<List<Map<String, Object>>> orders(@RequestParam(required = false) String date,
                                                    @RequestParam(required = false) String status) {
        LocalDate day;
        try {
            day = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        } catch (java.time.format.DateTimeParseException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "日期格式应为 yyyy-MM-dd");
        }
        LambdaQueryWrapper<Booking> query = new LambdaQueryWrapper<Booking>()
                .eq(Booking::getType, BookingType.COURT)
                .apply("EXISTS (SELECT 1 FROM booking_item i WHERE i.booking_id = booking.id "
                        + "AND i.start_at >= {0} AND i.start_at < {1})", day.atStartOfDay(), day.plusDays(1).atStartOfDay())
                .orderByAsc(Booking::getId);
        if (status != null && !status.isBlank()) {
            try {
                query.eq(Booking::getStatus, BookingStatus.valueOf(status));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "订单状态不合法");
            }
        }
        List<Booking> bookings = bookingMapper.selectList(query);
        if (bookings.isEmpty()) {
            return Result.ok(List.of());
        }
        Map<Long, List<BookingItem>> itemMap = bookingItemMapper.selectList(
                        new LambdaQueryWrapper<BookingItem>()
                                .in(BookingItem::getBookingId,
                                        bookings.stream().map(Booking::getId).toList())
                                .orderByAsc(BookingItem::getStartAt))
                .stream().collect(Collectors.groupingBy(BookingItem::getBookingId));
        Map<Long, User> userMap = userMapper.selectBatchIds(
                        bookings.stream().map(Booking::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, u -> u));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Booking booking : bookings) {
            List<BookingItem> items = itemMap.getOrDefault(booking.getId(), List.of());
            User user = userMap.get(booking.getUserId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", booking.getId());
            row.put("bookingNo", booking.getBookingNo());
            row.put("status", booking.getStatus().name());
            row.put("userName", user != null ? user.getNickname() : "");
            row.put("userPhone", user != null ? user.getPhone() : "");
            row.put("title", items.isEmpty() ? "" : items.get(0).getTitle());
            row.put("startAt", items.isEmpty() ? null : items.get(0).getStartAt());
            row.put("endAt", items.isEmpty() ? null : items.get(items.size() - 1).getEndAt());
            row.put("paidCents", booking.getPaidCents());
            row.put("payableCents", booking.getPayableCents());
            row.put("createdAt", booking.getCreatedAt());
            row.put("canCancel", booking.getStatus() == BookingStatus.PENDING_PAYMENT
                    || booking.getStatus() == BookingStatus.CONFIRMED);
            result.add(row);
        }
        return Result.ok(result);
    }

    /** 订单详情：主信息 + 明细 + 状态流水 + 支付/退款 */
    @GetMapping("/orders/{id}")
    public Result<Map<String, Object>> orderDetail(@PathVariable Long id) {
        Booking booking = bookingMapper.selectById(id);
        if (booking == null) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND);
        }
        User user = userMapper.selectById(booking.getUserId());
        List<BookingItem> items = bookingItemMapper.selectList(new LambdaQueryWrapper<BookingItem>()
                .eq(BookingItem::getBookingId, id).orderByAsc(BookingItem::getStartAt));
        List<BookingStatusHistory> history = historyMapper.selectList(
                new LambdaQueryWrapper<BookingStatusHistory>()
                        .eq(BookingStatusHistory::getBookingId, id)
                        .orderByAsc(BookingStatusHistory::getId));
        List<PayOrder> pays = payOrderMapper.selectList(new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getBookingId, id).orderByAsc(PayOrder::getId));
        List<RefundOrder> refunds = refundOrderMapper.selectList(new LambdaQueryWrapper<RefundOrder>()
                .eq(RefundOrder::getBookingId, id).orderByAsc(RefundOrder::getId));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("booking", booking);
        data.put("userName", user != null ? user.getNickname() : "");
        data.put("userPhone", user != null ? user.getPhone() : "");
        data.put("items", items);
        data.put("history", history);
        data.put("pays", pays);
        data.put("refunds", refunds);
        return Result.ok(data);
    }

    /** 强制取消：场馆原因全退，写审计 */
    @PostMapping("/orders/{id}/force-cancel")
    public Result<Map<String, Object>> forceCancel(@PathVariable Long id, @RequestBody(required = false) CancelRequest req) {
        Booking booking = bookingMapper.selectById(id);
        if (booking == null) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND);
        }
        if (booking.getType() != BookingType.COURT) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "当前仅支持强制取消场地订单，教练/团课/拼场请走对应管理");
        }
        String reason = req == null || req.getReason() == null || req.getReason().isBlank()
                ? "场馆原因取消" : req.getReason();
        int refund = 0;
        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            // 先抢占状态，避免并发下重复退款
            if (bookingMapper.markCancelledConfirmed(id, "ADMIN_" + reason) == 0) {
                throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "订单状态已变化，请刷新后重试");
            }
            if (booking.getPaidCents() != null && booking.getPaidCents() > 0) {
                refund = booking.getPaidCents();
                refundService.refundForReschedule(booking, refund, reason);
            }
        } else if (booking.getStatus() == BookingStatus.PENDING_PAYMENT) {
            if (bookingMapper.markCancelled(id, "ADMIN_" + reason) == 0) {
                throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "订单状态已变化，请刷新后重试");
            }
        } else {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "当前状态不能取消");
        }
        slotMapper.releaseBookedByBooking(id);
        slotMapper.releaseByBooking(id);
        audit.write(id, booking.getStatus().name(), BookingStatus.CANCELLED.name(), "ADMIN", reason);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refundCents", refund);
        data.put("bookingId", id);
        return Result.ok(data);
    }

    /** 关场：占用中必须先看受影响订单；force=true 时全退并取消 */
    @PostMapping("/slots/{slotId}/close")
    public Result<Map<String, Object>> closeSlot(@PathVariable Long slotId,
                                                 @RequestBody(required = false) CloseRequest req) {
        Slot slot = slotMapper.selectById(slotId);
        if (slot == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "时段不存在");
        }
        boolean force = req != null && req.isForce();
        String reason = req == null || req.getReason() == null || req.getReason().isBlank()
                ? "场馆原因关场" : req.getReason();
        Map<String, Object> data = new LinkedHashMap<>();
        if (slot.getStatus() == SlotStatus.BOOKED || slot.getStatus() == SlotStatus.HELD) {
            List<Map<String, Object>> affected = new ArrayList<>();
            if ("BOOKING".equals(slot.getOwnerType()) && slot.getOwnerId() != null) {
                Booking booking = bookingMapper.selectById(slot.getOwnerId());
                if (booking != null && booking.getStatus() != BookingStatus.CANCELLED) {
                    User user = userMapper.selectById(booking.getUserId());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("bookingId", booking.getId());
                    row.put("userName", user != null ? user.getNickname() : "");
                    row.put("userPhone", user != null ? user.getPhone() : "");
                    row.put("status", booking.getStatus().name());
                    affected.add(row);
                }
            }
            if (!force && !affected.isEmpty()) {
                data.put("blocked", true);
                data.put("affected", affected);
                data.put("message", "该时段已被预订，确认后将全额退款并取消订单");
                return Result.ok(data);
            }
            for (Map<String, Object> row : affected) {
                forceCancel((Long) row.get("bookingId"), cancelRequest(reason));
            }
        }
        slot.setStatus(SlotStatus.CLOSED);
        slot.setHoldToken(null);
        slot.setHoldExpiresAt(null);
        slot.setOwnerType(null);
        slot.setOwnerId(null);
        slotMapper.updateById(slot);
        data.put("blocked", false);
        data.put("closed", true);
        data.put("slotId", slotId);
        return Result.ok(data);
    }

    @PostMapping("/slots/{slotId}/reopen")
    public Result<Void> reopenSlot(@PathVariable Long slotId) {
        Slot slot = slotMapper.selectById(slotId);
        if (slot == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "时段不存在");
        }
        if (slot.getStatus() != SlotStatus.CLOSED) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "只有已关闭的时段可以开放");
        }
        slot.setStatus(SlotStatus.AVAILABLE);
        slotMapper.updateById(slot);
        return Result.ok();
    }

    /** 改价：只改当前格子的价格快照，不影响已售出生效规则 */
    @PatchMapping("/slots/{slotId}/price")
    public Result<Void> changePrice(@PathVariable Long slotId, @RequestBody PriceRequest req) {
        Slot slot = slotMapper.selectById(slotId);
        if (slot == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "时段不存在");
        }
        if (slot.getStatus() != SlotStatus.AVAILABLE) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "只有可订状态的时段可以改价");
        }
        if (req.getPriceCents() == null || req.getPriceCents() <= 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "价格必须大于 0");
        }
        slot.setPriceCents(req.getPriceCents());
        slotMapper.updateById(slot);
        return Result.ok();
    }

    private CancelRequest cancelRequest(String reason) {
        CancelRequest request = new CancelRequest();
        request.setReason(reason);
        return request;
    }

    @Data
    public static class CancelRequest {
        private String reason;
    }

    @Data
    public static class CloseRequest {
        private boolean force;
        private String reason;
    }

    @Data
    public static class PriceRequest {
        private Integer priceCents;
    }
}
