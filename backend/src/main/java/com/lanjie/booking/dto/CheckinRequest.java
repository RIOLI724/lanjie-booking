package com.lanjie.booking.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CheckinRequest {

    @NotBlank(message = "核销码不能为空")
    private String token;
}
