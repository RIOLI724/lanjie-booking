package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lanjie.booking.enums.BookingItemType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("booking_item")
public class BookingItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bookingId;

    private BookingItemType itemType;

    private Long refId;

    private String title;

    private LocalDateTime startAt;

    private LocalDateTime endAt;

    private Integer unitPriceCents;

    private Integer amountCents;

    private Integer refundAmountCents;

    private LocalDateTime createdAt;
}
