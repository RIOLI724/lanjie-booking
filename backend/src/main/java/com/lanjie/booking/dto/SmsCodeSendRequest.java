package com.lanjie.booking.dto;

import com.lanjie.booking.enums.SmsScene;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class SmsCodeSendRequest {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "1\\d{10}", message = "手机号格式不正确")
    private String phone;

    @NotNull(message = "验证码场景不能为空")
    private SmsScene scene;
}
