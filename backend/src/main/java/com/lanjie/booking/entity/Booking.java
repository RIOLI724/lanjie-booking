package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lanjie.booking.enums.BookingStatus;
import com.lanjie.booking.enums.BookingType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("booking")
public class Booking {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String bookingNo;

    private Long userId;

    private Long venueId;

    private BookingType type;

    private BookingStatus status;

    private Integer listAmountCents;

    private Integer discountCents;

    private Integer payableCents;

    private Integer paidCents;

    private String contactName;

    private String contactPhone;

    private LocalDateTime holdExpiresAt;

    private LocalDateTime checkedInAt;

    private String cancelReason;

    private Integer rescheduleCount;

    /** 一次性核销码，核销成功立即作废 */
    private String checkinToken;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
