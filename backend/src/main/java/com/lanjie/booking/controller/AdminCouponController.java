package com.lanjie.booking.controller;

import com.lanjie.booking.common.Result;
import com.lanjie.booking.service.CouponService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/coupons")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCouponController {

    private final CouponService couponService;

    public AdminCouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @PostMapping("/grant")
    public Result<Void> grant(@Valid @RequestBody GrantRequest request) {
        couponService.grant(request.getUserId(), request.getTemplateId());
        return Result.ok();
    }

    @Data
    public static class GrantRequest {
        @NotNull(message = "用户不能为空")
        private Long userId;
        @NotNull(message = "券模板不能为空")
        private Long templateId;
    }
}
