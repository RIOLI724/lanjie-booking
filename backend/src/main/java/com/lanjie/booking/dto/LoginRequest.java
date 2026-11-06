package com.lanjie.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class LoginRequest {

    @NotNull(message = "登录方式不能为空")
    private GrantType grantType;

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "1\\d{10}", message = "手机号格式不正确")
    private String phone;

    /** grantType = PASSWORD 时必填 */
    private String password;

    /** grantType = SMS 时必填 */
    private String code;
}
