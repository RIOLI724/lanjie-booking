package com.lanjie.booking.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 业务错误码。分段约定：400xx 参数/通用，401xx 认证与会话，403xx 权限，
 * 404xx 订单与资源，409xx 并发冲突。
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    SYSTEM_ERROR(50000, "系统繁忙，请稍后再试"),
    PARAM_INVALID(40000, "参数不正确"),

    AUTH_SEND_TOO_FREQUENT(40101, "发送太频繁，请 60 秒后再试"),
    AUTH_CODE_INVALID(40102, "验证码错误或已过期"),
    AUTH_CODE_LOCKED(40103, "验证码错误次数过多，请 15 分钟后再试"),
    USER_ALREADY_EXISTS(40104, "该手机号已注册，请直接登录"),
    USER_NOT_FOUND(40105, "账号不存在或密码错误"),
    USER_FROZEN(40106, "账号已被冻结，请联系前台"),
    TOKEN_INVALID(40107, "登录已失效，请重新登录"),
    PHONE_INVALID(40108, "手机号格式不正确");

    private final int code;
    private final String message;
}
