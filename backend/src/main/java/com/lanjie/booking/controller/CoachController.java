package com.lanjie.booking.controller;

import com.lanjie.booking.common.Result;
import com.lanjie.booking.dto.CoachHoldRequest;
import com.lanjie.booking.dto.PackagePurchaseRequest;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.service.CoachBookingService;
import com.lanjie.booking.service.CoachQueryService;
import com.lanjie.booking.service.CoachSlotGenerationService;
import com.lanjie.booking.service.IdempotencyService;
import com.lanjie.booking.service.PackageService;
import com.lanjie.booking.service.PaymentService;
import com.lanjie.booking.vo.CoachPackageVO;
import com.lanjie.booking.vo.CoachSlotVO;
import com.lanjie.booking.vo.CoachVO;
import com.lanjie.booking.vo.HoldVO;
import com.lanjie.booking.vo.PayVO;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class CoachController {

    private final CoachQueryService coachQueryService;
    private final CoachBookingService coachBookingService;
    private final CoachSlotGenerationService coachSlotGenerationService;
    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;

    public CoachController(CoachQueryService coachQueryService, CoachBookingService coachBookingService,
                           CoachSlotGenerationService coachSlotGenerationService,
                           PaymentService paymentService, IdempotencyService idempotencyService) {
        this.coachQueryService = coachQueryService;
        this.coachBookingService = coachBookingService;
        this.coachSlotGenerationService = coachSlotGenerationService;
        this.paymentService = paymentService;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping("/coaches")
    public Result<List<CoachVO>> coaches() {
        return Result.ok(coachQueryService.coaches());
    }

    @GetMapping("/coaches/{id}")
    public Result<CoachVO> coach(@PathVariable Long id) {
        return Result.ok(coachQueryService.coach(id));
    }

    @GetMapping("/coaches/{id}/slots")
    public Result<List<CoachSlotVO>> slots(@PathVariable Long id,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.ok(coachQueryService.slots(id, date));
    }

    @GetMapping("/me/packages")
    public Result<List<CoachPackageVO>> myPackages(@AuthenticationPrincipal LoginUser loginUser) {
        return Result.ok(coachQueryService.myPackages(loginUser.userId()));
    }

    @PostMapping("/coach-bookings/holds")
    public Result<HoldVO> hold(@AuthenticationPrincipal LoginUser loginUser,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
                               @Valid @RequestBody CoachHoldRequest request) {
        HoldVO holdVO = idempotencyService.execute(idemKey, loginUser.userId(),
                "POST /coach-bookings/holds", request, HoldVO.class,
                () -> coachBookingService.hold(loginUser, request));
        return Result.ok(holdVO);
    }

    @PostMapping("/coach-packages")
    public Result<PayVO> purchasePackage(@AuthenticationPrincipal LoginUser loginUser,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
                                         @Valid @RequestBody PackagePurchaseRequest request) {
        PayVO payVO = idempotencyService.execute(idemKey, loginUser.userId(),
                "POST /coach-packages", request, PayVO.class,
                () -> paymentService.createPackagePayment(loginUser, request));
        return Result.ok(payVO);
    }

    /** 重建教练档期：仅管理员（避免顾客触发全量重建） */
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/coaches/{id}/regenerate")
    public Result<Void> regenerate(@PathVariable Long id) {
        coachSlotGenerationService.regenerateForCoach(id);
        return Result.ok();
    }
}
