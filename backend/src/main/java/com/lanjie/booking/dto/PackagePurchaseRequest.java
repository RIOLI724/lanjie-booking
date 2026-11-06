package com.lanjie.booking.dto;

import com.lanjie.booking.enums.PayChannel;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PackagePurchaseRequest {

    @NotNull(message = "请选择教练")
    private Long coachId;

    /** 5 / 10 / 20 节 */
    @NotNull(message = "请选择课包档位")
    private Integer sessions;

    @NotNull(message = "支付渠道不能为空")
    private PayChannel channel;
}
