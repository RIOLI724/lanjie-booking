package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.entity.CouponTemplate;
import com.lanjie.booking.entity.UserCoupon;
import com.lanjie.booking.enums.BookingType;
import com.lanjie.booking.mapper.CouponTemplateMapper;
import com.lanjie.booking.mapper.UserCouponMapper;
import com.lanjie.booking.vo.CouponVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 优惠券：一单一张、校验范围/门槛/有效期；全退返券，部分退款不返 */
@Slf4j
@Service
public class CouponService {

    private final CouponTemplateMapper templateMapper;
    private final UserCouponMapper userCouponMapper;

    public CouponService(CouponTemplateMapper templateMapper, UserCouponMapper userCouponMapper) {
        this.templateMapper = templateMapper;
        this.userCouponMapper = userCouponMapper;
    }

    @Transactional
    public void grant(Long userId, Long templateId) {
        CouponTemplate template = templateMapper.selectById(templateId);
        if (template == null || !"ACTIVE".equals(template.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "券模板不可用");
        }
        long held = userCouponMapper.selectCount(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getTemplateId, templateId));
        if (template.getPerUserLimit() > 0 && held >= template.getPerUserLimit()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "超过每人限领数量");
        }
        if (userCouponMapper.incrIssued(templateId) == 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "券已领完");
        }
        UserCoupon coupon = new UserCoupon();
        coupon.setUserId(userId);
        coupon.setTemplateId(templateId);
        coupon.setStatus("UNUSED");
        coupon.setExpiresAt(template.getValidTo() != null
                ? template.getValidTo() : LocalDateTime.now().plusDays(30));
        userCouponMapper.insert(coupon);
    }

    public List<CouponVO> myCoupons(Long userId) {
        return userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                        .eq(UserCoupon::getUserId, userId)
                        .orderByAsc(UserCoupon::getStatus)
                        .orderByDesc(UserCoupon::getId))
                .stream()
                .map(coupon -> {
                    CouponTemplate template = templateMapper.selectById(coupon.getTemplateId());
                    boolean expired = coupon.getStatus().equals("UNUSED")
                            && coupon.getExpiresAt().isBefore(LocalDateTime.now());
                    String status = expired ? "EXPIRED" : coupon.getStatus();
                    return new CouponVO(coupon.getId(), coupon.getTemplateId(),
                            template != null ? template.getName() : "优惠券",
                            template != null ? template.getType() : "FIXED",
                            template != null ? template.getValueCents() : 0,
                            template != null ? template.getPercent() : 100,
                            template != null ? template.getMaxDiscountCents() : 0,
                            template != null ? template.getMinAmountCents() : 0,
                            template != null ? template.getScope() : "ALL",
                            status, coupon.getExpiresAt().toString());
                })
                .toList();
    }

    /** 计算券面抵扣；不满足条件返回 0（不抛错，交给“二选一”择优逻辑） */
    public int discountFor(UserCoupon coupon, CouponTemplate template, int listAmount, BookingType type) {
        if (coupon == null || template == null) {
            return 0;
        }
        if (!"UNUSED".equals(coupon.getStatus()) || coupon.getExpiresAt().isBefore(LocalDateTime.now())) {
            return 0;
        }
        if (listAmount < template.getMinAmountCents()) {
            return 0;
        }
        if (!"ALL".equals(template.getScope()) && !template.getScope().equals(type.name())) {
            return 0;
        }
        if ("FIXED".equals(template.getType())) {
            return Math.min(template.getValueCents(), listAmount);
        }
        int discount = (int) ((long) listAmount * (100 - template.getPercent()) / 100);
        if (template.getMaxDiscountCents() > 0) {
            discount = Math.min(discount, template.getMaxDiscountCents());
        }
        return discount;
    }

    public UserCoupon ownCoupon(Long userId, Long couponId) {
        UserCoupon coupon = userCouponMapper.selectById(couponId);
        if (coupon == null || !coupon.getUserId().equals(userId)) {
            return null;
        }
        return coupon;
    }

    public CouponTemplate templateOf(Long templateId) {
        return templateMapper.selectById(templateId);
    }

    /** 支付成功：占用券（条件更新防一券多用） */
    public void use(Long userId, Long couponId, Long bookingId) {
        if (userCouponMapper.markUsed(couponId, userId, bookingId) == 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "优惠券不可用");
        }
    }

    /** 全退返券 */
    public void restore(Long couponId) {
        if (couponId != null) {
            userCouponMapper.restore(couponId);
        }
    }
}
