package com.lanjie.booking.controller;

import com.lanjie.booking.common.Result;
import com.lanjie.booking.dto.CheckinRequest;
import com.lanjie.booking.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台（管理端）操作：扫码核销。ADMIN 角色限定，用户管理/退款记录等后台页 W11 陆续接入。
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminBookingController {

    private final BookingService bookingService;

    public AdminBookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping("/bookings/{id}/checkin")
    public Result<Void> checkin(@PathVariable Long id, @Valid @RequestBody CheckinRequest request) {
        bookingService.adminCheckin(id, request.getToken());
        return Result.ok();
    }
}
