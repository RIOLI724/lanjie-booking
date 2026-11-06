package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.entity.Coach;
import com.lanjie.booking.entity.CoachPackage;
import com.lanjie.booking.entity.CoachService;
import com.lanjie.booking.entity.PayOrder;
import com.lanjie.booking.enums.CoachServiceType;
import com.lanjie.booking.enums.PackageStatus;
import com.lanjie.booking.enums.PayChannel;
import com.lanjie.booking.enums.PayOrderStatus;
import com.lanjie.booking.enums.PayPurpose;
import com.lanjie.booking.mapper.CoachMapper;
import com.lanjie.booking.mapper.CoachPackageMapper;
import com.lanjie.booking.mapper.CoachServiceMapper;
import com.lanjie.booking.mapper.PayOrderMapper;
import com.lanjie.booking.vo.PayVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * 课包：5/10/20 节 = 95/90/85 折，12 个月有效。创建待支付课包 + 支付单；
 * 支付确认（模拟回调）或钱包即时支付后激活。
 */
@Slf4j
@Service
public class PackageService {

    private static final Map<Integer, Integer> RATES = Map.of(5, 95, 10, 90, 20, 85);
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final CoachMapper coachMapper;
    private final CoachServiceMapper coachServiceMapper;
    private final CoachPackageMapper coachPackageMapper;
    private final PayOrderMapper payOrderMapper;
    private final WalletService walletService;

    public PackageService(CoachMapper coachMapper, CoachServiceMapper coachServiceMapper,
                          CoachPackageMapper coachPackageMapper, PayOrderMapper payOrderMapper,
                          WalletService walletService) {
        this.coachMapper = coachMapper;
        this.coachServiceMapper = coachServiceMapper;
        this.coachPackageMapper = coachPackageMapper;
        this.payOrderMapper = payOrderMapper;
        this.walletService = walletService;
    }

    /** 创建待支付课包 + 支付单；channel = WALLET 时即时扣款激活 */
    @Transactional
    public PayVO purchase(Long userId, Long coachId, Integer sessions, PayChannel channel) {
        int rate = RATES.get(sessions) != null ? RATES.get(sessions) : 0;
        if (rate == 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "课包档位为 5/10/20 节");
        }
        Coach coach = coachMapper.selectById(coachId);
        if (coach == null || !"ACTIVE".equals(coach.getStatus())) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND, "教练不存在");
        }
        CoachService service = coachServiceMapper.selectOne(new LambdaQueryWrapper<CoachService>()
                .eq(CoachService::getCoachId, coachId)
                .eq(CoachService::getType, CoachServiceType.PRIVATE)
                .eq(CoachService::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (service == null) {
            throw new BusinessException(ErrorCode.COACH_SERVICE_INVALID);
        }
        int price = (int) ((long) coach.getBasePriceCents() * sessions * rate / 100);

        CoachPackage pkg = new CoachPackage();
        pkg.setUserId(userId);
        pkg.setCoachId(coachId);
        pkg.setServiceId(service.getId());
        pkg.setTotalSessions(sessions);
        pkg.setUsedSessions(0);
        pkg.setRemainingSessions(sessions);
        pkg.setPriceCents(price);
        pkg.setExpiresAt(LocalDateTime.now().plusDays(365));
        pkg.setStatus(PackageStatus.PENDING_PAYMENT);
        coachPackageMapper.insert(pkg);

        PayOrder payOrder = new PayOrder();
        payOrder.setBookingId(null);
        payOrder.setBizRef(pkg.getId());
        payOrder.setUserId(userId);
        payOrder.setPurpose(PayPurpose.COACH_PACKAGE);
        payOrder.setChannel(channel);
        payOrder.setAmountCents(price);
        payOrder.setStatus(PayOrderStatus.PENDING);
        insertPayOrder(payOrder);

        if (channel == PayChannel.WALLET) {
            walletService.debit(userId, price, com.lanjie.booking.enums.WalletTxnType.PAY,
                    "PAY_ORDER", payOrder.getId());
            if (coachPackageMapper.activate(pkg.getId(), payOrder.getId()) == 0) {
                throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "课包已激活");
            }
            if (payOrderMapper.markPaid(payOrder.getId(), "MOCK" + uuid8(), LocalDateTime.now()) == 0) {
                throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "支付单状态已变化");
            }
        }
        log.info("package purchase: payNo={} user={} coach={} sessions={} price={}",
                payOrder.getPayNo(), userId, coachId, sessions, price);
        return new PayVO(payOrder.getPayNo(), price, channel.name());
    }

    private void insertPayOrder(PayOrder payOrder) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                payOrder.setPayNo("PY" + LocalDateTime.now().format(NO_TIME)
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
                payOrderMapper.insert(payOrder);
                return;
            } catch (DuplicateKeyException e) {
                // 单号冲突重试
            }
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR);
    }

    private String uuid8() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
