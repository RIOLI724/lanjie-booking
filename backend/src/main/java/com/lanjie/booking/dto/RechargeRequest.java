package com.lanjie.booking.dto;

import com.lanjie.booking.enums.PayChannel;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RechargeRequest {

    @NotNull(message = "请选择充值档位")
    private Long planId;

    @NotNull(message = "支付渠道不能为空")
    private PayChannel channel;
}
