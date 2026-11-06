package com.lanjie.booking.dto;

/** 登录/注册/刷新的统一返回 */
public record LoginVO(String accessToken, String refreshToken, String tokenType, long expiresIn) {
}
