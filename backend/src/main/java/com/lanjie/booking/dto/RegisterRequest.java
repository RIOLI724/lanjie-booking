package com.lanjie.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "1\\d{10}", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "验证码不能为空")
    @Size(min = 4, max = 6, message = "验证码格式不正确")
    private String code;

    /** 可选；设置了才能用密码登录 */
    @Size(min = 6, max = 32, message = "密码长度 6-32 位")
    private String password;

    /** 可选；缺省为「球友+尾号」 */
    @Size(max = 20, message = "昵称最长 20 字")
    private String nickname;
}
