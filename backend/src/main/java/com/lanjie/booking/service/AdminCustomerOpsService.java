package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.entity.Coach;
import com.lanjie.booking.entity.CoachPackage;
import com.lanjie.booking.entity.CoachService;
import com.lanjie.booking.entity.CouponTemplate;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.entity.UserCoupon;
import com.lanjie.booking.enums.PackageStatus;
import com.lanjie.booking.enums.WalletTxnType;
import com.lanjie.booking.mapper.CoachMapper;
import com.lanjie.booking.mapper.CoachPackageMapper;
import com.lanjie.booking.mapper.CoachServiceMapper;
import com.lanjie.booking.mapper.CouponTemplateMapper;
import com.lanjie.booking.mapper.UserCouponMapper;
import com.lanjie.booking.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/** 前台客户运营：补课包、看已发券。 */
@Slf4j
@Service
public class AdminCustomerOpsService {

    private final UserMapper userMapper;
    private final CoachMapper coachMapper;
    private final CoachServiceMapper coachServiceMapper;
    private final CoachPackageMapper packageMapper;
    private final UserCouponMapper userCouponMapper;
    private final CouponTemplateMapper couponTemplateMapper;
    private final WalletService walletService;

    public AdminCustomerOpsService(UserMapper userMapper, CoachMapper coachMapper,
                                   CoachServiceMapper coachServiceMapper, CoachPackageMapper packageMapper,
                                   UserCouponMapper userCouponMapper, CouponTemplateMapper couponTemplateMapper,
                                   WalletService walletService) {
        this.userMapper = userMapper;
        this.coachMapper = coachMapper;
        this.coachServiceMapper = coachServiceMapper;
        this.packageMapper = packageMapper;
        this.userCouponMapper = userCouponMapper;
        this.couponTemplateMapper = couponTemplateMapper;
        this.walletService = walletService;
    }

    /** 补偿/赠送课包：不收款，按 12 个月有效期直接生效。 */
    @Transactional
    public Long grantPackage(Long userId, Long coachId, Long serviceId, int sessions) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        if (sessions < 1 || sessions > 50) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "赠送课时应在 1–50 节之间");
        }
        Coach coach = coachMapper.selectById(coachId);
        if (coach == null || !"ACTIVE".equals(coach.getStatus())) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND, "教练不存在或已停用");
        }
        CoachService service = coachServiceMapper.selectById(serviceId);
        if (service == null || !service.getCoachId().equals(coachId) || !"ACTIVE".equals(service.getStatus())) {
            throw new BusinessException(ErrorCode.COACH_SERVICE_INVALID);
        }

        CoachPackage pkg = new CoachPackage();
        pkg.setUserId(userId);
        pkg.setCoachId(coachId);
        pkg.setServiceId(serviceId);
        pkg.setTotalSessions(sessions);
        pkg.setUsedSessions(0);
        pkg.setRemainingSessions(sessions);
        pkg.setPriceCents(0);
        pkg.setExpiresAt(LocalDateTime.now().plusMonths(12));
        pkg.setStatus(PackageStatus.ACTIVE);
        packageMapper.insert(pkg);
        log.info("granted coach package: user={} coach={} sessions={}", userId, coachId, sessions);
        return pkg.getId();
    }

    /** 前台调余额：正数入账、负数扣减，写 ADJUST 流水，余额不足直接拒绝。 */
    @Transactional
    public long adjustWallet(Long userId, int amountCents) {
        if (userMapper.selectById(userId) == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        long abs = Math.abs((long) amountCents);
        if (abs == 0 || abs > 1_000_000) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "单次调整需在 0.01–10000 元之间");
        }
        long bizId = System.currentTimeMillis() * 1000 + ThreadLocalRandom.current().nextInt(1000);
        if (amountCents > 0) {
            walletService.credit(userId, amountCents, WalletTxnType.ADJUST, "ADMIN_ADJUST", bizId);
        } else {
            walletService.debit(userId, abs, WalletTxnType.ADJUST, "ADMIN_ADJUST", bizId);
        }
        return walletService.balanceOf(userId).balanceCents();
    }

    public List<Map<String, Object>> coupons(Long userId) {        Map<Long, CouponTemplate> templates = couponTemplateMapper.selectList(null).stream()
                .collect(Collectors.toMap(CouponTemplate::getId, t -> t));
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserCoupon coupon : userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .orderByDesc(UserCoupon::getId))) {
            CouponTemplate template = templates.get(coupon.getTemplateId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", coupon.getId());
            row.put("name", template != null ? template.getName() : "优惠券");
            row.put("status", coupon.getStatus());
            row.put("expiresAt", coupon.getExpiresAt());
            row.put("receivedAt", coupon.getReceivedAt());
            result.add(row);
        }
        return result;
    }
}
