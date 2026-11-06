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
    PHONE_INVALID(40108, "手机号格式不正确"),

    IDEMPOTENCY_CONFLICT(40005, "请勿重复提交"),

    SLOT_TAKEN(40901, "该时段刚被抢订，请换一个时段"),
    SLOT_NOT_CONTIGUOUS(40902, "只能连选同一场地的连续时段"),
    SLOT_TOO_MANY(40903, "一次最多连选 4 格"),
    SLOT_IN_PAST(40904, "不能预订已过去的时段"),
    BOOKING_DAILY_LIMIT(40905, "每天最多预订 2 单场地"),

    BOOKING_NOT_FOUND(40401, "订单不存在"),
    BOOKING_STATUS_INVALID(40402, "订单状态不支持该操作"),
    BOOKING_HOLD_EXPIRED(40403, "锁位已超时，请重新选场"),
    BOOKING_CANCEL_UNSUPPORTED(40404, "已支付订单的取消与退款即将开放"),
    PAY_ORDER_NOT_FOUND(40411, "支付单不存在"),
    PAY_CHANNEL_UNSUPPORTED(40412, "该支付渠道暂未开放");

    private final int code;
    private final String message;
}
