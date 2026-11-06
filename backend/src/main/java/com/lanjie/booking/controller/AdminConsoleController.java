package com.lanjie.booking.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.Result;
import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.entity.BookingItem;
import com.lanjie.booking.entity.RefundOrder;
import com.lanjie.booking.entity.Slot;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.entity.UserBan;
import com.lanjie.booking.enums.BookingStatus;
import com.lanjie.booking.enums.SlotStatus;
import com.lanjie.booking.mapper.BookingItemMapper;
import com.lanjie.booking.mapper.BookingMapper;
import com.lanjie.booking.mapper.RefundOrderMapper;
import com.lanjie.booking.mapper.SlotMapper;
import com.lanjie.booking.mapper.UserBanMapper;
import com.lanjie.booking.mapper.UserMapper;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理端查询：看板 / 订单 / 退款 / 用户封禁 / 排期（核销与 AI 观测另有控制器） */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminConsoleController {

    private final BookingMapper bookingMapper;
    private final BookingItemMapper bookingItemMapper;
    private final SlotMapper slotMapper;
    private final RefundOrderMapper refundOrderMapper;
    private final UserMapper userMapper;
    private final UserBanMapper userBanMapper;

    public AdminConsoleController(BookingMapper bookingMapper, BookingItemMapper bookingItemMapper,
                                  SlotMapper slotMapper, RefundOrderMapper refundOrderMapper,
                                  UserMapper userMapper, UserBanMapper userBanMapper) {
        this.bookingMapper = bookingMapper;
        this.bookingItemMapper = bookingItemMapper;
        this.slotMapper = slotMapper;
        this.refundOrderMapper = refundOrderMapper;
        this.userMapper = userMapper;
        this.userBanMapper = userBanMapper;
    }

    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard() {
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        List<Booking> todayBookings = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .ge(Booking::getCreatedAt, dayStart)
                .lt(Booking::getCreatedAt, dayEnd));
        long revenue = todayBookings.stream()
                .filter(b -> b.getStatus() == BookingStatus.CONFIRMED
                        || b.getStatus() == BookingStatus.COMPLETED)
                .mapToLong(Booking::getPaidCents).sum();
        long confirmed = todayBookings.stream()
                .filter(b -> b.getStatus() == BookingStatus.CONFIRMED).count();
        long cancelled = todayBookings.stream()
                .filter(b -> b.getStatus() == BookingStatus.CANCELLED).count();
        long totalSlots = slotMapper.selectCount(new LambdaQueryWrapper<Slot>()
                .eq(Slot::getSlotDate, LocalDate.now()));
        long bookedSlots = slotMapper.selectCount(new LambdaQueryWrapper<Slot>()
                .eq(Slot::getSlotDate, LocalDate.now())
                .eq(Slot::getStatus, SlotStatus.BOOKED));
        long occupancy = totalSlots == 0 ? 0 : bookedSlots * 100 / totalSlots;
        long noShows = todayBookings.stream()
                .filter(b -> b.getStatus() == BookingStatus.NO_SHOW).count();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("todayOrders", todayBookings.size());
        data.put("todayRevenueCents", revenue);
        data.put("confirmed", confirmed);
        data.put("cancelled", cancelled);
        data.put("noShows", noShows);
        data.put("occupancyPct", occupancy);
        data.put("totalSlots", totalSlots);
        data.put("bookedSlots", bookedSlots);
        return Result.ok(data);
    }

    @GetMapping("/bookings")
    public Result<List<Map<String, Object>>> bookings(@RequestParam(required = false) String status) {
        LambdaQueryWrapper<Booking> query = new LambdaQueryWrapper<Booking>()
                .orderByDesc(Booking::getId).last("LIMIT 100");
        if (status != null && !status.isBlank()) {
            query.eq(Booking::getStatus, status);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Booking booking : bookingMapper.selectList(query)) {
            BookingItem item = bookingItemMapper.selectOne(new LambdaQueryWrapper<BookingItem>()
                    .eq(BookingItem::getBookingId, booking.getId())
                    .orderByAsc(BookingItem::getStartAt).last("LIMIT 1"));
            User user = userMapper.selectById(booking.getUserId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", booking.getId());
            row.put("bookingNo", booking.getBookingNo());
            row.put("type", booking.getType().name());
            row.put("status", booking.getStatus().name());
            row.put("title", item != null ? item.getTitle() : "");
            row.put("startAt", item != null ? item.getStartAt().toString() : "");
            row.put("payableCents", booking.getPayableCents());
            row.put("paidCents", booking.getPaidCents());
            row.put("nickname", user != null ? user.getNickname() : "");
            row.put("phone", user != null ? user.getPhone() : "");
            row.put("checkedIn", booking.getCheckedInAt() != null);
            result.add(row);
        }
        return Result.ok(result);
    }

    @GetMapping("/refunds")
    public Result<List<RefundOrder>> refunds() {
        return Result.ok(refundOrderMapper.selectList(new LambdaQueryWrapper<RefundOrder>()
                .orderByDesc(RefundOrder::getId).last("LIMIT 100")));
    }

    @GetMapping("/users")
    public Result<List<Map<String, Object>>> users() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (User user : userMapper.selectList(new LambdaQueryWrapper<User>()
                .orderByDesc(User::getId).last("LIMIT 100"))) {
            UserBan ban = userBanMapper.selectOne(new LambdaQueryWrapper<UserBan>()
                    .eq(UserBan::getUserId, user.getId())
                    .gt(UserBan::getEndAt, LocalDateTime.now())
                    .last("LIMIT 1"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", user.getId());
            row.put("nickname", user.getNickname());
            row.put("phone", user.getPhone());
            row.put("role", user.getRole().name());
            row.put("status", user.getStatus().name());
            row.put("bannedUntil", ban != null ? ban.getEndAt().toString() : null);
            row.put("banReason", ban != null ? ban.getReason() : null);
            result.add(row);
        }
        return Result.ok(result);
    }

    @PostMapping("/users/{id}/ban")
    public Result<Void> ban(@PathVariable Long id, @RequestBody BanRequest request) {
        if (request.isUnban()) {
            userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<User>()
                    .eq(User::getId, id).set(User::getStatus,
                            com.lanjie.booking.enums.UserStatus.ACTIVE));
            return Result.ok();
        }
        userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<User>()
                .eq(User::getId, id).set(User::getStatus,
                        com.lanjie.booking.enums.UserStatus.FROZEN));
        if (request.getDays() > 0) {
            UserBan ban = new UserBan();
            ban.setUserId(id);
            ban.setReason(request.getReason() == null ? "管理员封禁" : request.getReason());
            ban.setStartAt(LocalDateTime.now());
            ban.setEndAt(LocalDateTime.now().plusDays(request.getDays()));
            userBanMapper.insert(ban);
        }
        return Result.ok();
    }

    @Data
    public static class BanRequest {
        private boolean unban;
        private int days;
        private String reason;
    }
}
