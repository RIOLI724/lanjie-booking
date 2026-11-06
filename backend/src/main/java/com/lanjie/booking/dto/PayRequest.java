package com.lanjie.booking.dto;

import com.lanjie.booking.enums.PayChannel;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PayRequest {

    @NotNull(message = "支付渠道不能为空")
    private PayChannel channel;
}
