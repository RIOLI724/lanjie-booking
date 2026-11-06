package com.lanjie.booking.common;

import lombok.Getter;

/**
 * 业务异常：HTTP 仍返回 200，靠 Result.code 区分；只有鉴权失败走 401。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }
}
