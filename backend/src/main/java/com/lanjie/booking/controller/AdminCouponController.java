package com.lanjie.booking.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.Result;
import com.lanjie.booking.entity.CouponTemplate;
import com.lanjie.booking.mapper.CouponTemplateMapper;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.service.AdminAuditService;
import com.lanjie.booking.service.CouponService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/coupons")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCouponController {

    private final CouponService couponService;
    private final CouponTemplateMapper templateMapper;
    private final AdminAuditService auditService;

    public AdminCouponController(CouponService couponService, CouponTemplateMapper templateMapper,
                                 AdminAuditService auditService) {
        this.couponService = couponService;
        this.templateMapper = templateMapper;
        this.auditService = auditService;
    }

    @GetMapping("/templates")
    public Result<List<CouponTemplate>> templates() {
        return Result.ok(templateMapper.selectList(new LambdaQueryWrapper<CouponTemplate>()
                .eq(CouponTemplate::getStatus, "ACTIVE")
                .orderByAsc(CouponTemplate::getId)));
    }

    @PostMapping("/grant")
    public Result<Void> grant(@AuthenticationPrincipal LoginUser actor,
                              @Valid @RequestBody GrantRequest request) {
        couponService.grant(request.getUserId(), request.getTemplateId());
        CouponTemplate template = templateMapper.selectById(request.getTemplateId());
        auditService.record(actor.userId(), "CUSTOMER_GRANT_COUPON", "USER", request.getUserId(),
                "发券：" + (template != null ? template.getName() : request.getTemplateId()));
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
