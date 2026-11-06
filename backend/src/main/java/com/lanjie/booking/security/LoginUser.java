package com.lanjie.booking.security;

/** 当前登录用户（安全上下文中的 principal） */
public record LoginUser(Long userId, String phone, String role) {
}
