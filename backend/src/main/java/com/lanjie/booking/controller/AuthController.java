package com.lanjie.booking.controller;

import com.lanjie.booking.common.Result;
import com.lanjie.booking.dto.GrantType;
import com.lanjie.booking.dto.LoginRequest;
import com.lanjie.booking.dto.LoginVO;
import com.lanjie.booking.dto.RefreshRequest;
import com.lanjie.booking.dto.RegisterRequest;
import com.lanjie.booking.dto.SmsCodeSendRequest;
import com.lanjie.booking.service.AuthService;
import com.lanjie.booking.service.SmsCodeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final SmsCodeService smsCodeService;

    public AuthController(AuthService authService, SmsCodeService smsCodeService) {
        this.authService = authService;
        this.smsCodeService = smsCodeService;
    }

    @PostMapping("/sms-code")
    public Result<Void> sendSmsCode(@Valid @RequestBody SmsCodeSendRequest request) {
        smsCodeService.send(request.getPhone(), request.getScene());
        return Result.ok();
    }

    @PostMapping("/register")
    public Result<LoginVO> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public Result<LoginVO> refresh(@Valid @RequestBody RefreshRequest request) {
        return Result.ok(authService.refresh(request.getRefreshToken()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.getRefreshToken());
        return Result.ok();
    }
}
