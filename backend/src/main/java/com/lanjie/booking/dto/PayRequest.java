package com.lanjie.booking.dto;

import com.lanjie.booking.enums.PayChannel;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PayRequest {

    @NotNull(message = "支付渠道不能为空")
    private PayChannel channel;

    /** channel = PACKAGE 时必填：要核销的课包 */
    private Long packageId;
}
