package com.lanjie.booking.common;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 统一响应包装。code = 0 表示成功；业务错误码见 {@link ErrorCode}。
 */
@Data
@AllArgsConstructor
public class Result<T> {

    public static final int SUCCESS = 0;

    private int code;
    private String message;
    private T data;

    public static <T> Result<T> ok(T data) {
        return new Result<>(SUCCESS, "ok", data);
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
