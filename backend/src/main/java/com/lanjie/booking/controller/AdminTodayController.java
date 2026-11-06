package com.lanjie.booking.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.Result;
import com.lanjie.booking.entity.AiEvalCase;
import com.lanjie.booking.entity.AiEvalResult;
import com.lanjie.booking.entity.AiEvalRun;
import com.lanjie.booking.entity.AiHandoff;
import com.lanjie.booking.entity.AiMessage;
import com.lanjie.booking.entity.AiSession;
import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.entity.BookingItem;
import com.lanjie.booking.entity.CoachPackage;
import com.lanjie.booking.entity.Court;
import com.lanjie.booking.entity.RefundOrder;
import com.lanjie.booking.entity.Slot;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.entity.UserBan;
import com.lanjie.booking.entity.WalletAccount;
import com.lanjie.booking.enums.BookingStatus;
import com.lanjie.booking.enums.PackageStatus;
import com.lanjie.booking.enums.SlotStatus;
import com.lanjie.booking.mapper.AiEvalCaseMapper;
import com.lanjie.booking.mapper.AiEvalResultMapper;
import com.lanjie.booking.mapper.AiEvalRunMapper;
import com.lanjie.booking.mapper.AiHandoffMapper;
import com.lanjie.booking.mapper.AiMessageMapper;
import com.lanjie.booking.mapper.AiSessionMapper;
import com.lanjie.booking.mapper.BookingItemMapper;
import com.lanjie.booking.mapper.BookingMapper;
import com.lanjie.booking.mapper.CoachPackageMapper;
import com.lanjie.booking.mapper.CourtMapper;
import com.lanjie.booking.mapper.RefundOrderMapper;
import com.lanjie.booking.mapper.SlotMapper;
import com.lanjie.booking.mapper.UserMapper;
import com.lanjie.booking.mapper.UserBanMapper;
import com.lanjie.booking.mapper.WalletAccountMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前台工作台（人话版）：今日待办 / 排期 / 客户 / AI 质量摘要。
 * 面向岗位任务组织数据，不暴露工程师视角的会话号与毫秒。
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTodayController {

    private final BookingMapper bookingMapper;
    private final BookingItemMapper bookingItemMapper;
    private final SlotMapper slotMapper;
    private final CourtMapper courtMapper;
    private final UserMapper userMapper;
    private final UserBanMapper userBanMapper;
    private final AiHandoffMapper handoffMapper;
    private final AiMessageMapper messageMapper;
    private final AiSessionMapper sessionMapper;
    private final RefundOrderMapper refundOrderMapper;
    private final WalletAccountMapper walletMapper;
    private final CoachPackageMapper packageMapper;
    private final AiEvalRunMapper evalRunMapper;
    private final AiEvalResultMapper evalResultMapper;
    private final AiEvalCaseMapper evalCaseMapper;

    public AdminTodayController(BookingMapper bookingMapper, BookingItemMapper bookingItemMapper,
                                SlotMapper slotMapper, CourtMapper courtMapper, UserMapper userMapper,
                                UserBanMapper userBanMapper,
                                AiHandoffMapper handoffMapper, AiMessageMapper messageMapper,
                                AiSessionMapper sessionMapper, RefundOrderMapper refundOrderMapper,
                                WalletAccountMapper walletMapper, CoachPackageMapper packageMapper,
                                AiEvalRunMapper evalRunMapper, AiEvalResultMapper evalResultMapper,
                                AiEvalCaseMapper evalCaseMapper) {
        this.bookingMapper = bookingMapper;
        this.bookingItemMapper = bookingItemMapper;
        this.slotMapper = slotMapper;
        this.courtMapper = courtMapper;
        this.userMapper = userMapper;
        this.userBanMapper = userBanMapper;
        this.handoffMapper = handoffMapper;
        this.messageMapper = messageMapper;
        this.sessionMapper = sessionMapper;
        this.refundOrderMapper = refundOrderMapper;
        this.walletMapper = walletMapper;
        this.packageMapper = packageMapper;
        this.evalRunMapper = evalRunMapper;
        this.evalResultMapper = evalResultMapper;
        this.evalCaseMapper = evalCaseMapper;
    }

    @GetMapping("/today")
    public Result<Map<String, Object>> today() {
        LocalDate date = LocalDate.now();
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        List<BookingItem> items = bookingItemMapper.selectList(new LambdaQueryWrapper<BookingItem>()
                .ge(BookingItem::getStartAt, dayStart).lt(BookingItem::getStartAt, dayEnd)
                .orderByAsc(BookingItem::getStartAt));

        List<Map<String, Object>> checkinQueue = new ArrayList<>();
        long revenue = 0;
        for (BookingItem item : items) {
            Booking booking = bookingMapper.selectById(item.getBookingId());
            if (booking == null) {
                continue;
            }
            if (booking.getStatus() == BookingStatus.CONFIRMED
                    || booking.getStatus() == BookingStatus.COMPLETED) {
                revenue += booking.getPaidCents() == null ? 0 : booking.getPaidCents();
            }
            if (booking.getStatus() == BookingStatus.CONFIRMED && booking.getCheckedInAt() == null) {
                User user = userMapper.selectById(booking.getUserId());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("bookingId", booking.getId());
                row.put("time", item.getStartAt().toLocalTime().toString().substring(0, 5));
                row.put("title", item.getTitle());
                row.put("userName", user != null ? user.getNickname() : "顾客");
                row.put("userPhone", user != null ? user.getPhone() : "");
                checkinQueue.add(row);
            }
        }

        List<Map<String, Object>> handoffQueue = new ArrayList<>();
        for (AiHandoff handoff : handoffMapper.selectList(new LambdaQueryWrapper<AiHandoff>()
                .eq(AiHandoff::getStatus, "OPEN").orderByDesc(AiHandoff::getId).last("LIMIT 20"))) {
            User user = userMapper.selectById(handoff.getUserId());
            // 评测与自测使用管理员账号，不进入前台待办
            if (user != null && user.getRole() == com.lanjie.booking.enums.UserRole.ADMIN) {
                continue;
            }
            AiMessage lastUser = messageMapper.selectOne(new LambdaQueryWrapper<AiMessage>()
                    .eq(AiMessage::getSessionId, handoff.getSessionId())
                    .eq(AiMessage::getRole, "USER")
                    .orderByDesc(AiMessage::getId).last("LIMIT 1"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("handoffId", handoff.getId());
            row.put("userName", user != null ? user.getNickname() : "顾客");
            row.put("userPhone", user != null ? user.getPhone() : "");
            row.put("reason", handoff.getReason());
            row.put("lastWords", lastUser != null ? lastUser.getContent() : "");
            row.put("createdAt", handoff.getCreatedAt().toString());
            handoffQueue.add(row);
        }

        List<RefundOrder> pendingRefunds = refundOrderMapper.selectList(new LambdaQueryWrapper<RefundOrder>()
                .in(RefundOrder::getStatus, "PENDING", "FAILED")
                .orderByDesc(RefundOrder::getId).last("LIMIT 10"));

        long noShowsToday = bookingMapper.selectCount(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getStatus, BookingStatus.NO_SHOW)
                .ge(Booking::getUpdatedAt, dayStart).lt(Booking::getUpdatedAt, dayEnd));
        long pendingPayment = bookingMapper.selectCount(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getStatus, BookingStatus.PENDING_PAYMENT)
                .lt(Booking::getHoldExpiresAt, LocalDateTime.now()));

        long totalSlots = slotMapper.selectCount(new LambdaQueryWrapper<Slot>().eq(Slot::getSlotDate, date));
        long bookedSlots = slotMapper.selectCount(new LambdaQueryWrapper<Slot>()
                .eq(Slot::getSlotDate, date).eq(Slot::getStatus, SlotStatus.BOOKED));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("revenueCents", revenue);
        summary.put("checkinCount", checkinQueue.size());
        summary.put("handoffCount", handoffQueue.size());
        summary.put("noShows", noShowsToday);
        summary.put("overduePending", pendingPayment);
        summary.put("occupancyPct", totalSlots == 0 ? 0 : bookedSlots * 100 / totalSlots);
        summary.put("bookedSlots", bookedSlots);
        summary.put("totalSlots", totalSlots);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", summary);
        data.put("checkinQueue", checkinQueue);
        data.put("handoffQueue", handoffQueue);
        data.put("refundQueue", pendingRefunds);
        return Result.ok(data);
    }

    /** 今日/某日排期：4 片场 × 时段，谁订的、空着、已关 */
    @GetMapping("/schedule")
    public Result<List<Map<String, Object>>> schedule(@RequestParam(required = false) String date) {
        LocalDate day = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        List<Court> courts = courtMapper.selectList(new LambdaQueryWrapper<Court>()
                .eq(Court::getStatus, "ACTIVE").orderByAsc(Court::getSortOrder));
        List<Slot> slots = slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                .eq(Slot::getSlotDate, day).orderByAsc(Slot::getStartAt));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Court court : courts) {
            List<Map<String, Object>> cells = new ArrayList<>();
            for (Slot slot : slots) {
                if (!slot.getCourtId().equals(court.getId())) {
                    continue;
                }
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("time", slot.getStartAt().toLocalTime().toString().substring(0, 5));
                cell.put("slotId", slot.getId());
                cell.put("status", slot.getStatus().name());
                cell.put("priceCents", slot.getPriceCents());
                if ("BOOKING".equals(slot.getOwnerType()) && slot.getOwnerId() != null) {
                    Booking booking = bookingMapper.selectById(slot.getOwnerId());
                    User user = booking != null ? userMapper.selectById(booking.getUserId()) : null;
                    cell.put("bookingId", booking != null ? booking.getId() : null);
                    cell.put("userName", user != null ? user.getNickname() : "");
                }
                cells.add(cell);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("courtName", court.getName());
            row.put("cells", cells);
            result.add(row);
        }
        return Result.ok(result);
    }

    /** 客户 360：手机号一搜，订单/余额/课包全看清 */
    @GetMapping("/customer")
    public Result<Map<String, Object>> customer(@RequestParam String phone) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, phone).last("LIMIT 1"));
        if (user == null) {
            return Result.ok(Map.of());
        }
        WalletAccount wallet = walletMapper.selectOne(new LambdaQueryWrapper<WalletAccount>()
                .eq(WalletAccount::getUserId, user.getId()).last("LIMIT 1"));
        List<CoachPackage> packages = packageMapper.selectList(new LambdaQueryWrapper<CoachPackage>()
                .eq(CoachPackage::getUserId, user.getId())
                .in(CoachPackage::getStatus, PackageStatus.ACTIVE, PackageStatus.EXHAUSTED));
        List<Map<String, Object>> bookings = new ArrayList<>();
        for (Booking booking : bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getUserId, user.getId()).orderByDesc(Booking::getId).last("LIMIT 10"))) {
            BookingItem item = bookingItemMapper.selectOne(new LambdaQueryWrapper<BookingItem>()
                    .eq(BookingItem::getBookingId, booking.getId())
                    .orderByAsc(BookingItem::getStartAt).last("LIMIT 1"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bookingId", booking.getId());
            row.put("status", booking.getStatus().name());
            row.put("title", item != null ? item.getTitle() : "");
            row.put("paidCents", booking.getPaidCents());
            bookings.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", user.getId());
        data.put("userName", user.getNickname());
        data.put("phone", user.getPhone());
        data.put("role", user.getRole().name());
        data.put("status", user.getStatus().name());
        UserBan activeBan = userBanMapper.selectOne(new LambdaQueryWrapper<UserBan>()
                .eq(UserBan::getUserId, user.getId())
                .gt(UserBan::getEndAt, LocalDateTime.now())
                .orderByDesc(UserBan::getId)
                .last("LIMIT 1"));
        data.put("bannedUntil", activeBan != null ? activeBan.getEndAt() : null);
        data.put("walletCents", wallet != null ? wallet.getBalanceCents() : 0);
        data.put("packages", packages);
        data.put("bookings", bookings);
        return Result.ok(data);
    }

    /** AI 质量摘要：人话版，给前台/负责人看 */
    @GetMapping("/ai/summary")
    public Result<Map<String, Object>> aiSummary() {
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        // 只统计真实顾客：评测/自测走的是管理员账号，不算“AI 接待”
        long sessionsToday = 0;
        for (AiSession session : sessionMapper.selectList(new LambdaQueryWrapper<AiSession>()
                .ge(AiSession::getStartedAt, dayStart))) {
            User user = userMapper.selectById(session.getUserId());
            if (user == null || user.getRole() != com.lanjie.booking.enums.UserRole.ADMIN) {
                sessionsToday++;
            }
        }
        long handoffsToday = 0;
        for (AiHandoff handoff : handoffMapper.selectList(new LambdaQueryWrapper<AiHandoff>()
                .ge(AiHandoff::getCreatedAt, dayStart))) {
            User user = userMapper.selectById(handoff.getUserId());
            if (user == null || user.getRole() != com.lanjie.booking.enums.UserRole.ADMIN) {
                handoffsToday++;
            }
        }
        AiEvalRun latest = evalRunMapper.selectOne(new LambdaQueryWrapper<AiEvalRun>()
                .orderByDesc(AiEvalRun::getId).last("LIMIT 1"));
        List<Map<String, Object>> failedCases = new ArrayList<>();
        if (latest != null) {
            for (AiEvalResult result : evalResultMapper.selectList(new LambdaQueryWrapper<AiEvalResult>()
                    .eq(AiEvalResult::getRunId, latest.getId()).eq(AiEvalResult::getPassed, false))) {
                AiEvalCase evalCase = evalCaseMapper.selectById(result.getCaseId());
                failedCases.add(Map.of("question", evalCase != null ? evalCase.getInput() : "",
                        "answer", result.getActualAnswer() == null ? "" : result.getActualAnswer()));
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionsToday", sessionsToday);
        data.put("handoffsToday", handoffsToday);
        data.put("evalPassed", latest != null ? latest.getPassed() : 0);
        data.put("evalTotal", latest != null ? latest.getTotal() : 0);
        data.put("evalAt", latest != null ? latest.getStartedAt().toString() : null);
        data.put("failedCases", failedCases);
        return Result.ok(data);
    }
}
