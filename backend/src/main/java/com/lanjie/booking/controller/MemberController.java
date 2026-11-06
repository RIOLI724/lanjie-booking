package com.lanjie.booking.controller;

import com.lanjie.booking.common.Result;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.service.CouponService;
import com.lanjie.booking.service.MembershipService;
import com.lanjie.booking.service.PointsService;
import com.lanjie.booking.vo.CouponVO;
import com.lanjie.booking.vo.MemberVO;
import com.lanjie.booking.vo.PointsTxnVO;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/me")
public class MemberController {

    private final MembershipService membershipService;
    private final PointsService pointsService;
    private final CouponService couponService;

    public MemberController(MembershipService membershipService, PointsService pointsService,
                            CouponService couponService) {
        this.membershipService = membershipService;
        this.pointsService = pointsService;
        this.couponService = couponService;
    }

    @GetMapping("/member")
    public Result<MemberVO> member(@AuthenticationPrincipal LoginUser loginUser) {
        return Result.ok(membershipService.memberVO(loginUser.userId()));
    }

    @GetMapping("/points/transactions")
    public Result<List<PointsTxnVO>> pointsTxns(@AuthenticationPrincipal LoginUser loginUser) {
        return Result.ok(pointsService.transactions(loginUser.userId()));
    }

    @GetMapping("/coupons")
    public Result<List<CouponVO>> coupons(@AuthenticationPrincipal LoginUser loginUser) {
        return Result.ok(couponService.myCoupons(loginUser.userId()));
    }
}
