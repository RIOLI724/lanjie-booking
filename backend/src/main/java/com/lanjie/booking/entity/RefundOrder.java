package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lanjie.booking.enums.PayChannel;
import com.lanjie.booking.enums.RefundStatus;
import lombok.Data;

import java.time.LocalDateTime;

/** 退款单：与原支付同路（钱包退钱包，微信退微信） */
@Data
@TableName("refund_order")
public class RefundOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String refundNo;

    private Long payOrderId;

    private Long bookingId;

    private Long userId;

    private Integer amountCents;

    private String reason;

    private PayChannel channel;

    private RefundStatus status;

    private String thirdPartyRefundId;

    private LocalDateTime createdAt;

    private LocalDateTime completedAt;
}
