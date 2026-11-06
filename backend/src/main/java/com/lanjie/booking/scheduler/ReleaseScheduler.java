package com.lanjie.booking.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.enums.BookingStatus;
import com.lanjie.booking.mapper.BookingMapper;
import com.lanjie.booking.mapper.CoachSlotMapper;
import com.lanjie.booking.mapper.SlotMapper;
import com.lanjie.booking.service.BookingAuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 释放双保险（每分钟）：① 兜底释放所有过期 HELD 格子；② 超时未支付的待支付订单自动取消并释放格子。
 * 全部条件更新，天然幂等；调度器漏跑一次也不会超卖。
 */
@Slf4j
@Component
public class ReleaseScheduler {

    private final BookingMapper bookingMapper;
    private final SlotMapper slotMapper;
    private final CoachSlotMapper coachSlotMapper;
    private final BookingAuditService audit;

    public ReleaseScheduler(BookingMapper bookingMapper, SlotMapper slotMapper,
                            CoachSlotMapper coachSlotMapper, BookingAuditService audit) {
        this.bookingMapper = bookingMapper;
        this.slotMapper = slotMapper;
        this.coachSlotMapper = coachSlotMapper;
        this.audit = audit;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        int releasedHolds = slotMapper.releaseExpired(now);
        int releasedCoachHolds = coachSlotMapper.releaseExpired(now);

        List<Booking> expired = bookingMapper.selectList(new LambdaQueryWrapper<Booking>()
                .eq(Booking::getStatus, BookingStatus.PENDING_PAYMENT)
                .lt(Booking::getHoldExpiresAt, now));
        int cancelled = 0;
        for (Booking booking : expired) {
            if (bookingMapper.markCancelled(booking.getId(), "HOLD_EXPIRED") > 0) {
                slotMapper.releaseByBooking(booking.getId());
                audit.write(booking.getId(), BookingStatus.PENDING_PAYMENT.name(),
                        BookingStatus.CANCELLED.name(), "SYSTEM", "超时未支付，自动取消");
                cancelled++;
            }
        }
        if (releasedHolds > 0 || cancelled > 0) {
            log.info("release sweep: releasedHolds={}, expiredBookings={}", releasedHolds, cancelled);
        }
    }
}
