package com.lanjie.booking.controller;

import com.lanjie.booking.common.Result;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.service.AdminAuditService;
import com.lanjie.booking.service.AdminCustomerOpsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 管理端：客户运营动作（补课包、已发券查询）。 */
@RestController
@RequestMapping("/api/v1/admin/customers")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCustomerController {

    private final AdminCustomerOpsService opsService;
    private final AdminAuditService auditService;

    public AdminCustomerController(AdminCustomerOpsService opsService, AdminAuditService auditService) {
        this.opsService = opsService;
        this.auditService = auditService;
    }

    @PostMapping("/{userId}/packages")
    public Result<Long> grantPackage(@PathVariable Long userId,
                                     @AuthenticationPrincipal LoginUser actor,
                                     @Valid @RequestBody GrantPackageRequest request) {
        Long packageId = opsService.grantPackage(userId, request.getCoachId(),
                request.getServiceId(), request.getSessions());
        auditService.record(actor.userId(), "CUSTOMER_GRANT_PACKAGE", "USER", userId,
                "赠送课包 " + request.getSessions() + " 节，教练 " + request.getCoachId()
                        + "，原因：" + (request.getReason() == null ? "客户补偿" : request.getReason()));
        return Result.ok(packageId);
    }

    @GetMapping("/{userId}/coupons")
    public Result<List<Map<String, Object>>> coupons(@PathVariable Long userId) {
        return Result.ok(opsService.coupons(userId));
    }

    /** 前台调余额：正数补款、负数扣款，写流水与审计。 */
    @PostMapping("/{userId}/wallet-adjust")
    public Result<Long> adjustWallet(@PathVariable Long userId,
                                     @AuthenticationPrincipal LoginUser actor,
                                     @Valid @RequestBody WalletAdjustRequest request) {
        long balance = opsService.adjustWallet(userId, request.getAmountCents());
        auditService.record(actor.userId(), "CUSTOMER_WALLET_ADJUST", "USER", userId,
                "调余额 " + request.getAmountCents() + " 分，原因：" + request.getReason());
        return Result.ok(balance);
    }

    @Data
    public static class WalletAdjustRequest {
        @NotNull(message = "调整金额不能为空")
        private Integer amountCents;
        @NotNull(message = "请填写调整原因")
        private String reason;
    }

    @Data
    public static class GrantPackageRequest {
        @NotNull(message = "教练不能为空")
        private Long coachId;
        @NotNull(message = "服务不能为空")
        private Long serviceId;
        @NotNull(message = "课时数不能为空")
        @Min(value = 1, message = "至少 1 节")
        @Max(value = 50, message = "最多 50 节")
        private Integer sessions;
        private String reason;
    }
}
