package com.lanjie.booking.controller;

import com.lanjie.booking.common.Result;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.service.PaymentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟支付网关：生产环境这个端点替换为真实渠道的验签回调（异步通知），
 * 幂等与状态机逻辑完全一致，只多一道验签。
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/mock/{payNo}/confirm")
    public Result<Void> confirmMock(@AuthenticationPrincipal LoginUser loginUser, @PathVariable String payNo) {
        paymentService.confirmMockPayment(loginUser, payNo);
        return Result.ok();
    }
}
