package com.lanjie.booking.controller;

import com.lanjie.booking.common.Result;
import com.lanjie.booking.dto.MeVO;
import com.lanjie.booking.dto.UpdateMeRequest;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MeController {

    private final AuthService authService;

    public MeController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/me")
    public Result<MeVO> me(@AuthenticationPrincipal LoginUser loginUser) {
        return Result.ok(authService.me(loginUser));
    }

    @PatchMapping("/me")
    public Result<MeVO> updateMe(@AuthenticationPrincipal LoginUser loginUser,
                                 @Valid @RequestBody UpdateMeRequest request) {
        return Result.ok(authService.updateMe(loginUser, request));
    }
}
