package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.entity.BookingItem;
import com.lanjie.booking.entity.PayOrder;
import com.lanjie.booking.entity.RefundOrder;
import com.lanjie.booking.enums.PayChannel;
import com.lanjie.booking.enums.PayOrderStatus;
import com.lanjie.booking.enums.PayPurpose;
import com.lanjie.booking.enums.RefundStatus;
import com.lanjie.booking.enums.WalletTxnType;
import com.lanjie.booking.mapper.BookingItemMapper;
import com.lanjie.booking.mapper.PayOrderMapper;
import com.lanjie.booking.mapper.RefundOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 退款：与取消同一事务，金额由阶梯规则计算，不信任前端传值；按原支付渠道原路退回。
 * 阶梯：开场前 ≥24h 全退 / 2–24h 退 70% / <2h 不退。
 */
@Slf4j
@Service
public class RefundService {

    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RefundOrderMapper refundOrderMapper;
    private final PayOrderMapper payOrderMapper;
    private final BookingItemMapper bookingItemMapper;
    private final WalletService walletService;
    private final CouponService couponService;
    private final PointsService pointsService;

    public RefundService(RefundOrderMapper refundOrderMapper, PayOrderMapper payOrderMapper,
                         BookingItemMapper bookingItemMapper, WalletService walletService,
                         CouponService couponService, PointsService pointsService) {
        this.refundOrderMapper = refundOrderMapper;
        this.payOrderMapper = payOrderMapper;
        this.bookingItemMapper = bookingItemMapper;
        this.walletService = walletService;
        this.couponService = couponService;
        this.pointsService = pointsService;
    }

    public int refundRatio(LocalDateTime firstStart, LocalDateTime now) {
        long minutes = Duration.between(now, firstStart).toMinutes();
        if (minutes >= 24 * 60L) {
            return 100;
        }
        if (minutes >= 2 * 60L) {
            return 70;
        }
        return 0;
    }

    /** 取消退款试算（不动账） */
    public int quoteRefund(Booking booking, LocalDateTime firstStart) {
        int ratio = refundRatio(firstStart, LocalDateTime.now());
        return (int) (booking.getPaidCents() * (long) ratio / 100);
    }

    /** 取消退款：按阶梯计算并原路退回，返回实退金额（0 表示不退）。全退返券；积分按比例返还 */
    @Transactional
    public int refundForCancel(Booking booking, LocalDateTime firstStart, String reason) {
        int ratio = refundRatio(firstStart, LocalDateTime.now());
        int refund = quoteRefund(booking, firstStart);
        if (refund > 0) {
            createRefund(booking, refund, reason);
            applyItemRefunds(booking.getId(), refund, booking.getPaidCents());
        }
        if (ratio == 100) {
            couponService.restore(booking.getCouponId());
            int points = booking.getPointsUsed() == null ? 0 : booking.getPointsUsed();
            pointsService.restoreSpend(booking.getUserId(), points, booking.getId());
        } else if (ratio > 0 && booking.getPointsUsed() != null && booking.getPointsUsed() > 0) {
            pointsService.restoreSpend(booking.getUserId(),
                    booking.getPointsUsed() * ratio / 100, booking.getId());
        }
        return refund;
    }

    /** 场馆原因 / 全额退款：全额原路退回，并返还优惠券与积分 */
    @Transactional
    public int refundFull(Booking booking, String reason) {
        int paid = booking.getPaidCents() == null ? 0 : booking.getPaidCents();
        if (paid > 0) {
            createRefund(booking, paid, reason);
            applyItemRefunds(booking.getId(), paid, paid);
        }
        couponService.restore(booking.getCouponId());
        int points = booking.getPointsUsed() == null ? 0 : booking.getPointsUsed();
        pointsService.restoreSpend(booking.getUserId(), points, booking.getId());
        return paid;
    }

    /** 改期差价退回（新时段更便宜） */
    @Transactional
    public void refundForReschedule(Booking booking, int amount, String reason) {
        if (amount > 0) {
            createRefund(booking, amount, reason);
        }
    }

    private void createRefund(Booking booking, int amount, String reason) {
        PayOrder paidOrder = payOrderMapper.selectOne(new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getBookingId, booking.getId())
                .eq(PayOrder::getPurpose, PayPurpose.BOOKING)
                .eq(PayOrder::getStatus, PayOrderStatus.PAID)
                .orderByAsc(PayOrder::getId)
                .last("LIMIT 1"));
        if (paidOrder == null) {
            log.error("refund without paid order: booking={}", booking.getId());
            return;
        }
        RefundOrder refund = new RefundOrder();
        refund.setRefundNo("RF" + LocalDateTime.now().format(NO_TIME)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase());
        refund.setPayOrderId(paidOrder.getId());
        refund.setBookingId(booking.getId());
        refund.setUserId(booking.getUserId());
        refund.setAmountCents(amount);
        refund.setReason(reason);
        refund.setChannel(paidOrder.getChannel());
        refund.setStatus(RefundStatus.PENDING);
        refundOrderMapper.insert(refund);

        // 模拟渠道即时成功；真实渠道这里发起原路退款，等回调改状态
        refund.setThirdPartyRefundId("MOCKRF" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        refund.setStatus(RefundStatus.SUCCESS);
        refund.setCompletedAt(LocalDateTime.now());
        refundOrderMapper.updateById(refund);

        if (paidOrder.getChannel() == PayChannel.WALLET) {
            walletService.credit(booking.getUserId(), amount, WalletTxnType.REFUND, "REFUND_ORDER", refund.getId());
        }
        log.info("refund success: refundNo={} booking={} amount={} channel={}",
                refund.getRefundNo(), booking.getId(), amount, paidOrder.getChannel());
    }

    /** 明细按比例记已退金额（向下取整） */
    private void applyItemRefunds(Long bookingId, int totalRefund, int totalPaid) {
        if (totalPaid <= 0) {
            return;
        }
        List<BookingItem> items = bookingItemMapper.selectList(new LambdaQueryWrapper<BookingItem>()
                .eq(BookingItem::getBookingId, bookingId));
        for (BookingItem item : items) {
            int refund = (int) ((long) item.getAmountCents() * totalRefund / totalPaid);
            item.setRefundAmountCents(refund);
            bookingItemMapper.updateById(item);
        }
    }
}
