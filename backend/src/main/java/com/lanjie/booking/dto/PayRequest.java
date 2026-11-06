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

    /** 可选：优惠券（与会员折扣二选一，系统选优） */
    private Long couponId;

    /** 可选：积分抵扣（整百，最多抵应付 20%） */
    private Integer pointsToUse;
}
