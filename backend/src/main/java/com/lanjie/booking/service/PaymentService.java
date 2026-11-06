package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.dto.PayRequest;
import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.entity.PayOrder;
import com.lanjie.booking.enums.BookingStatus;
import com.lanjie.booking.enums.PayChannel;
import com.lanjie.booking.enums.PayOrderStatus;
import com.lanjie.booking.enums.PayPurpose;
import com.lanjie.booking.mapper.BookingMapper;
import com.lanjie.booking.mapper.PayOrderMapper;
import com.lanjie.booking.mapper.SlotMapper;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.vo.PayVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 支付：创建支付单 → 模拟网关确认（生产替换为验签回调）。
 * 幂等锚点：pay_order 条件更新 status PENDING → PAID，重放直接成功返回。
 */
@Slf4j
@Service
public class PaymentService {

    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final BookingMapper bookingMapper;
    private final PayOrderMapper payOrderMapper;
    private final SlotMapper slotMapper;
    private final BookingAuditService audit;

    public PaymentService(BookingMapper bookingMapper, PayOrderMapper payOrderMapper,
                          SlotMapper slotMapper, BookingAuditService audit) {
        this.bookingMapper = bookingMapper;
        this.payOrderMapper = payOrderMapper;
        this.slotMapper = slotMapper;
        this.audit = audit;
    }

    @Transactional
    public PayVO createPayment(LoginUser loginUser, Long bookingId, PayRequest request) {
        Booking booking = ownBooking(loginUser, bookingId);
        if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID);
        }
        if (booking.getHoldExpiresAt() == null || booking.getHoldExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.BOOKING_HOLD_EXPIRED);
        }
        if (request.getChannel() == PayChannel.WALLET) {
            throw new BusinessException(ErrorCode.PAY_CHANNEL_UNSUPPORTED);
        }
        PayOrder payOrder = new PayOrder();
        payOrder.setBookingId(booking.getId());
        payOrder.setUserId(booking.getUserId());
        payOrder.setPurpose(PayPurpose.BOOKING);
        payOrder.setChannel(request.getChannel());
        payOrder.setAmountCents(booking.getPayableCents());
        payOrder.setStatus(PayOrderStatus.PENDING);
        insertWithRetry(payOrder);
        log.info("pay order created: payNo={} booking={} amount={}",
                payOrder.getPayNo(), bookingId, payOrder.getAmountCents());
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
        // 先条件确认订单，再落支付成功：两把条件锁在同一事务，迟到的成功不会把钱落在已取消的订单上
        if (bookingMapper.markConfirmed(payOrder.getBookingId()) == 0) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "订单已取消，本次支付不会生效");
        }
        slotMapper.confirmByBooking(payOrder.getBookingId());
        if (payOrderMapper.markPaid(payOrder.getId(), "MOCK" + uuid8(), LocalDateTime.now()) == 0) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "支付单状态已变化");
        }
        audit.write(payOrder.getBookingId(), BookingStatus.PENDING_PAYMENT.name(),
                BookingStatus.CONFIRMED.name(), "USER", "支付成功 " + payNo);
        log.info("pay confirmed: payNo={} booking={}", payNo, payOrder.getBookingId());
    }

    private Booking ownBooking(LoginUser loginUser, Long bookingId) {
        Booking booking = bookingMapper.selectById(bookingId);
        if (booking == null || !booking.getUserId().equals(loginUser.userId())) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND);
        }
        return booking;
    }

    private void insertWithRetry(PayOrder payOrder) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (payOrder.getPayNo() == null) {
                    payOrder.setPayNo(newPayNo());
                }
                payOrderMapper.insert(payOrder);
                return;
            } catch (DuplicateKeyException e) {
                payOrder.setPayNo(null);
            }
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR);
    }

    private String newPayNo() {
        return "PY" + LocalDateTime.now().format(NO_TIME) + uuid8();
    }

    private String uuid8() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
