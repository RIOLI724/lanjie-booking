package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lanjie.booking.enums.PayChannel;
import com.lanjie.booking.enums.PayOrderStatus;
import com.lanjie.booking.enums.PayPurpose;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pay_order")
public class PayOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String payNo;

    private Long bookingId;

    /** 业务引用：BOOKING/RESCHEDULE_DIFF→booking_id，RECHARGE→plan_id */
    private Long bizRef;

    private Long userId;

    private PayPurpose purpose;

    private PayChannel channel;

    private Integer amountCents;

    private PayOrderStatus status;

    private String thirdPartyTxnId;

    private LocalDateTime paidAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
