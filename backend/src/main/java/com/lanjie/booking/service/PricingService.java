package com.lanjie.booking.service;

import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.entity.CouponTemplate;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.entity.UserCoupon;
import com.lanjie.booking.enums.BookingType;
import com.lanjie.booking.mapper.UserMapper;
import com.lanjie.booking.vo.PricingQuote;
import org.springframework.stereotype.Service;

/**
 * 计价引擎：会员折扣（仅场地/私教）与券二选一取更优，积分再抵（100 分=1 元，≤20%，整百），最低支付 1 分。
 */
@Service
public class PricingService {

    private static final int POINTS_PER_CENT = 1; // 100 分 = 100 分钱
    private static final int POINTS_CAP_PCT = 20;
    private static final int MIN_PAYABLE = 1;

    private final MembershipService membershipService;
    private final CouponService couponService;
    private final PointsService pointsService;
    private final UserMapper userMapper;

    public PricingService(MembershipService membershipService, CouponService couponService,
                          PointsService pointsService, UserMapper userMapper) {
        this.membershipService = membershipService;
        this.couponService = couponService;
        this.pointsService = pointsService;
        this.userMapper = userMapper;
    }

    public PricingQuote quote(Booking booking, Long couponId, Integer pointsToUse) {
        int list = booking.getListAmountCents();
        User user = userMapper.selectById(booking.getUserId());

        // 会员折扣：仅场地与私教；信用点以外的类型不参与
        int memberDiscount = 0;
        if (user != null && (booking.getType() == BookingType.COURT || booking.getType() == BookingType.COACH)) {
            int pct = membershipService.discountPctFor(user);
            memberDiscount = (int) ((long) list * (100 - pct) / 100);
        }

        // 券抵扣
        int couponDiscount = 0;
        UserCoupon coupon = couponId != null ? couponService.ownCoupon(booking.getUserId(), couponId) : null;
        CouponTemplate template = coupon != null ? couponService.templateOf(coupon.getTemplateId()) : null;
        if (coupon != null && template != null) {
            couponDiscount = couponService.discountFor(coupon, template, list, booking.getType());
        }

        // 二选一取更优（相等时用券）
        boolean useCoupon = couponDiscount > 0 && couponDiscount >= memberDiscount;
        int chosenDiscount = useCoupon ? couponDiscount : memberDiscount;
        int subtotal = list - chosenDiscount;

        // 积分抵扣：整百、不超过 subtotal 的 20%、不超过余额
        int balance = user != null ? pointsService.balanceOf(booking.getUserId()) : 0;
        int requested = pointsToUse == null ? 0 : Math.max(0, pointsToUse / 100 * 100);
        int cap = subtotal * POINTS_CAP_PCT / 100;
        int usable = Math.min(requested, Math.min(balance, cap));
        usable = usable / 100 * 100;
        int pointsDiscount = usable * POINTS_PER_CENT;

        int payable = Math.max(MIN_PAYABLE, subtotal - pointsDiscount);
        return new PricingQuote(list, memberDiscount, useCoupon ? couponDiscount : 0,
                pointsDiscount, usable, useCoupon ? couponId : null,
                chosenDiscount + pointsDiscount, payable);
    }
}
