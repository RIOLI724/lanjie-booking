package com.lanjie.booking.controller;

import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.common.Result;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.service.AdminAuditService;
import com.lanjie.booking.service.PickupService;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/** 管理端：拼场流局、发起人缺席、份子钱争议处置。 */
@RestController
@RequestMapping("/api/v1/admin/pickups")
@PreAuthorize("hasAnyRole('ADMIN', 'FRONT_DESK')")
public class AdminPickupController {

    private final PickupService pickupService;
    private final AdminAuditService auditService;

    public AdminPickupController(PickupService pickupService, AdminAuditService auditService) {
        this.pickupService = pickupService;
        this.auditService = auditService;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) String date,
                                                  @RequestParam(required = false) String status) {
        LocalDate day;
        try {
            day = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "日期格式应为 yyyy-MM-dd");
        }
        return Result.ok(pickupService.adminGames(day, status));
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(pickupService.adminDetail(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/abort")
    public Result<Integer> abort(@PathVariable Long id, @AuthenticationPrincipal LoginUser actor,
                                 @RequestBody(required = false) ReasonRequest request) {
        String reason = request == null || request.getReason() == null || request.getReason().isBlank()
                ? "场馆原因流局" : request.getReason();
        int refunded = pickupService.adminAbort(id, reason);
        auditService.record(actor.userId(), "PICKUP_ABORT", "PICKUP", id,
                "拼场流局，退款 " + refunded + " 人，原因：" + reason);
        return Result.ok(refunded);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/initiator-no-show")
    public Result<Integer> initiatorNoShow(@PathVariable Long id, @AuthenticationPrincipal LoginUser actor,
                                           @RequestBody(required = false) ReasonRequest request) {
        String reason = request == null || request.getReason() == null || request.getReason().isBlank()
                ? "发起人缺席" : request.getReason();
        int refunded = pickupService.adminInitiatorNoShow(id, reason);
        auditService.record(actor.userId(), "PICKUP_INITIATOR_NO_SHOW", "PICKUP", id,
                "发起人缺席，退 " + refunded + " 份份子钱，原因：" + reason);
        return Result.ok(refunded);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/participants/{participantId}/refund")
    public Result<Void> refundParticipant(@PathVariable Long id, @PathVariable Long participantId,
                                          @AuthenticationPrincipal LoginUser actor,
                                          @RequestBody(required = false) ReasonRequest request) {
        String reason = request == null || request.getReason() == null || request.getReason().isBlank()
                ? "份子钱争议退款" : request.getReason();
        pickupService.adminRefundParticipant(id, participantId, reason);
        auditService.record(actor.userId(), "PICKUP_REFUND_SHARE", "PICKUP", id,
                "退单份份子钱，participant=" + participantId + "，原因：" + reason);
        return Result.ok();
    }

    @Data
    public static class ReasonRequest {
        private String reason;
    }
}
