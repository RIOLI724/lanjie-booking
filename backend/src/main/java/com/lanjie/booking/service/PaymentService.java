package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.dto.PayRequest;
import com.lanjie.booking.dto.RechargeRequest;
import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.entity.PayOrder;
import com.lanjie.booking.entity.RechargePlan;
import com.lanjie.booking.entity.Slot;
import com.lanjie.booking.enums.BookingStatus;
import com.lanjie.booking.enums.PayChannel;
import com.lanjie.booking.enums.PayOrderStatus;
import com.lanjie.booking.enums.PayPurpose;
import com.lanjie.booking.enums.WalletTxnType;
import com.lanjie.booking.mapper.BookingMapper;
import com.lanjie.booking.mapper.PayOrderMapper;
import com.lanjie.booking.mapper.RechargePlanMapper;
import com.lanjie.booking.mapper.SlotMapper;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.vo.PayVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 支付：三种用途（订单 / 充值 / 改期差价）走同一张支付单、同一个确认入口；
 * 钱包是即时支付（扣款+完成在同一事务），模拟微信走确认端点（生产替换为验签回调）。
 * 幂等锚点：pay_order 条件更新 PENDING → PAID，重放直接成功返回。
 */
@Slf4j
@Service
public class PaymentService {

    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final BookingMapper bookingMapper;
    private final PayOrderMapper payOrderMapper;
    private final SlotMapper slotMapper;
    private final RechargePlanMapper rechargePlanMapper;
    private final WalletService walletService;
    private final BookingService bookingService;
    private final BookingAuditService audit;

    public PaymentService(BookingMapper bookingMapper, PayOrderMapper payOrderMapper,
                          SlotMapper slotMapper, RechargePlanMapper rechargePlanMapper,
                          WalletService walletService, BookingService bookingService,
                          BookingAuditService audit) {
        this.bookingMapper = bookingMapper;
        this.payOrderMapper = payOrderMapper;
        this.slotMapper = slotMapper;
        this.rechargePlanMapper = rechargePlanMapper;
        this.walletService = walletService;
        this.bookingService = bookingService;
        this.audit = audit;
    }

    @Transactional
    public PayVO createBookingPayment(LoginUser loginUser, Long bookingId, PayRequest request) {
        Booking booking = ownBooking(loginUser, bookingId);
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID);
        }
        if (booking.getHoldExpiresAt() == null || booking.getHoldExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.BOOKING_HOLD_EXPIRED);
        }
        PayOrder payOrder = createPayOrder(booking.getUserId(), bookingId, bookingId,
                PayPurpose.BOOKING, request.getChannel(), booking.getPayableCents());
        if (request.getChannel() == PayChannel.WALLET) {
            // 钱包即时支付：扣款与订单完成在同一事务，余额不足全部回滚
            walletService.debit(booking.getUserId(), booking.getPayableCents(),
                    WalletTxnType.PAY, "PAY_ORDER", payOrder.getId());
            confirmBookingPayment(payOrder);
        }
        return new PayVO(payOrder.getPayNo(), payOrder.getAmountCents(), payOrder.getChannel().name());
    }

    @Transactional
    public PayVO createRechargePayment(LoginUser loginUser, RechargeRequest request) {
        RechargePlan plan = rechargePlanMapper.selectById(request.getPlanId());
        if (plan == null || !"ACTIVE".equals(plan.getStatus())) {
            throw new BusinessException(ErrorCode.PLAN_NOT_FOUND);
        }
        if (request.getChannel() == PayChannel.WALLET) {
            throw new BusinessException(ErrorCode.PAY_CHANNEL_UNSUPPORTED);
        }
        PayOrder payOrder = createPayOrder(loginUser.userId(), null, plan.getId(),
                PayPurpose.RECHARGE, request.getChannel(), plan.getAmountCents());
        log.info("recharge pay order created: payNo={} plan={} amount={}",
                payOrder.getPayNo(), plan.getId(), plan.getAmountCents());
        return new PayVO(payOrder.getPayNo(), payOrder.getAmountCents(), payOrder.getChannel().name());
    }

    @Transactional
    public void confirmMockPayment(LoginUser loginUser, String payNo) {
        PayOrder payOrder = payOrderMapper.selectOne(new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getPayNo, payNo)
                .last("LIMIT 1"));
        if (payOrder == null || !payOrder.getUserId().equals(loginUser.userId())) {
            throw new BusinessException(ErrorCode.PAY_ORDER_NOT_FOUND);
        }
        if (payOrder.getStatus() == PayOrderStatus.PAID) {
            return; // 回调重放，幂等成功
        }
        if (payOrder.getStatus() != PayOrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "支付单已关闭");
        }
        switch (payOrder.getPurpose()) {
            case BOOKING -> confirmBookingPayment(payOrder);
            case RECHARGE -> confirmRecharge(payOrder);
            case RESCHEDULE_DIFF -> confirmRescheduleDiff(payOrder);
        }
    }

    /** 先条件确认订单，再落支付成功：迟到的成功不会把钱落在已取消的订单上 */
    private void confirmBookingPayment(PayOrder payOrder) {
        if (bookingMapper.markConfirmed(payOrder.getBookingId()) == 0) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "订单已取消，本次支付不会生效");
        }
        slotMapper.confirmByBooking(payOrder.getBookingId());
        finishPayOrder(payOrder);
        audit.write(payOrder.getBookingId(), BookingStatus.PENDING_PAYMENT.name(),
                BookingStatus.CONFIRMED.name(), "USER", "支付成功 " + payOrder.getPayNo());
        log.info("pay confirmed: payNo={} booking={}", payOrder.getPayNo(), payOrder.getBookingId());
    }

    private void confirmRecharge(PayOrder payOrder) {
        // 唯一键兜底：重复回调不会重复入账
        walletService.credit(payOrder.getUserId(), payOrder.getAmountCents(),
                WalletTxnType.RECHARGE, "PAY_ORDER", payOrder.getId());
        RechargePlan plan = rechargePlanMapper.selectById(payOrder.getBizRef());
        if (plan != null && plan.getBonusCents() > 0) {
            walletService.credit(payOrder.getUserId(), plan.getBonusCents(),
                    WalletTxnType.RECHARGE_BONUS, "PAY_ORDER", payOrder.getId());
        }
        finishPayOrder(payOrder);
        log.info("recharge confirmed: payNo={} user={} amount={}+bonus",
                payOrder.getPayNo(), payOrder.getUserId(), payOrder.getAmountCents());
    }

    private void confirmRescheduleDiff(PayOrder payOrder) {
        List<Slot> heldNew = slotMapper.selectList(new LambdaQueryWrapper<Slot>()
                .eq(Slot::getOwnerType, "BOOKING")
                .eq(Slot::getOwnerId, payOrder.getBizRef())
                .eq(Slot::getStatus, com.lanjie.booking.enums.SlotStatus.HELD));
        if (heldNew.isEmpty()) {
            throw new BusinessException(ErrorCode.BOOKING_HOLD_EXPIRED, "改期已超时，请重新改期");
        }
        finishPayOrder(payOrder);
        bookingService.finalizeReschedule(payOrder.getBizRef(), heldNew, payOrder.getAmountCents());
    }

    private void finishPayOrder(PayOrder payOrder) {
        if (payOrderMapper.markPaid(payOrder.getId(), "MOCK" + uuid8(), LocalDateTime.now()) == 0) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "支付单状态已变化");
        }
    }

    private PayOrder createPayOrder(Long userId, Long bookingId, Long bizRef,
                                    PayPurpose purpose, PayChannel channel, int amount) {
        PayOrder payOrder = new PayOrder();
        payOrder.setBookingId(bookingId);
        payOrder.setBizRef(bizRef);
        payOrder.setUserId(userId);
        payOrder.setPurpose(purpose);
        payOrder.setChannel(channel);
        payOrder.setAmountCents(amount);
        payOrder.setStatus(PayOrderStatus.PENDING);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                payOrder.setPayNo("PY" + LocalDateTime.now().format(NO_TIME) + uuid8());
                payOrderMapper.insert(payOrder);
                return payOrder;
            } catch (DuplicateKeyException e) {
                // 单号冲突重试
            }
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR);
    }

    private Booking ownBooking(LoginUser loginUser, Long bookingId) {
        Booking booking = bookingMapper.selectById(bookingId);
        if (booking == null || !booking.getUserId().equals(loginUser.userId())) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND);
        }
        return booking;
    }

    private String uuid8() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
