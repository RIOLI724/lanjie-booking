package com.lanjie.booking.controller;

import com.lanjie.booking.common.Result;
import com.lanjie.booking.dto.RechargeRequest;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.service.PaymentService;
import com.lanjie.booking.service.WalletService;
import com.lanjie.booking.vo.PayVO;
import com.lanjie.booking.vo.RechargePlanVO;
import com.lanjie.booking.vo.WalletTxnVO;
import com.lanjie.booking.vo.WalletVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.entity.RechargePlan;
import com.lanjie.booking.mapper.RechargePlanMapper;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class WalletController {

    private final WalletService walletService;
    private final PaymentService paymentService;
    private final RechargePlanMapper rechargePlanMapper;

    public WalletController(WalletService walletService, PaymentService paymentService,
                            RechargePlanMapper rechargePlanMapper) {
        this.walletService = walletService;
        this.paymentService = paymentService;
        this.rechargePlanMapper = rechargePlanMapper;
    }

    @GetMapping("/wallet")
    public Result<WalletVO> wallet(@AuthenticationPrincipal LoginUser loginUser) {
        return Result.ok(walletService.balanceOf(loginUser.userId()));
    }

    @GetMapping("/wallet/transactions")
    public Result<List<WalletTxnVO>> transactions(@AuthenticationPrincipal LoginUser loginUser) {
        return Result.ok(walletService.transactions(loginUser.userId()));
    }

    @GetMapping("/recharge-plans")
    public Result<List<RechargePlanVO>> rechargePlans() {
        return Result.ok(rechargePlanMapper.selectList(new LambdaQueryWrapper<RechargePlan>()
                .eq(RechargePlan::getStatus, "ACTIVE")
                .orderByAsc(RechargePlan::getSort))
                .stream()
                .map(p -> new RechargePlanVO(p.getId(), p.getAmountCents(), p.getBonusCents(), p.getTitle()))
                .toList());
    }

    @PostMapping("/wallet/recharge")
    public Result<PayVO> recharge(@AuthenticationPrincipal LoginUser loginUser,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
                                  @Valid @RequestBody RechargeRequest request) {
        PayVO payVO = paymentService.createRechargePayment(loginUser, request);
        return Result.ok(payVO);
    }
}
