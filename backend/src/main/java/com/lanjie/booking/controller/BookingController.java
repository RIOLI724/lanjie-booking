package com.lanjie.booking.controller;

import com.lanjie.booking.common.Result;
import com.lanjie.booking.dto.HoldRequest;
import com.lanjie.booking.dto.PayRequest;
import com.lanjie.booking.enums.BookingStatus;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.service.BookingService;
import com.lanjie.booking.service.IdempotencyService;
import com.lanjie.booking.service.PaymentService;
import com.lanjie.booking.vo.BookingVO;
import com.lanjie.booking.vo.CheckoutPreviewVO;
import com.lanjie.booking.vo.CheckinTokenVO;
import com.lanjie.booking.vo.HoldVO;
import com.lanjie.booking.vo.PayVO;
import com.lanjie.booking.vo.RescheduleVO;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;

    public BookingController(BookingService bookingService, PaymentService paymentService,
                             IdempotencyService idempotencyService) {
        this.bookingService = bookingService;
        this.paymentService = paymentService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/holds")
    public Result<HoldVO> hold(@AuthenticationPrincipal LoginUser loginUser,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
                               @Valid @RequestBody HoldRequest request) {
        HoldVO holdVO = idempotencyService.execute(idemKey, loginUser.userId(), "POST /bookings/holds",
                request, HoldVO.class, () -> bookingService.hold(loginUser, request));
        return Result.ok(holdVO);
    }

    @GetMapping
    public Result<List<BookingVO>> list(@AuthenticationPrincipal LoginUser loginUser,
                                        @RequestParam(required = false) BookingStatus status) {
        return Result.ok(bookingService.list(loginUser, status));
    }

    @GetMapping("/{id}")
    public Result<BookingVO> detail(@AuthenticationPrincipal LoginUser loginUser, @PathVariable Long id) {
        return Result.ok(bookingService.detail(loginUser, id));
    }

    @PostMapping("/{id}/checkout-preview")
    public Result<CheckoutPreviewVO> checkoutPreview(@AuthenticationPrincipal LoginUser loginUser,
                                                     @PathVariable Long id) {
        return Result.ok(bookingService.checkoutPreview(loginUser, id));
    }

    @PostMapping("/{id}/pay")
    public Result<PayVO> pay(@AuthenticationPrincipal LoginUser loginUser,
                             @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
                             @PathVariable Long id, @Valid @RequestBody PayRequest request) {
        PayVO payVO = idempotencyService.execute(idemKey, loginUser.userId(), "POST /bookings/" + id + "/pay",
                request, PayVO.class, () -> paymentService.createBookingPayment(loginUser, id, request));
        return Result.ok(payVO);
    }

    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@AuthenticationPrincipal LoginUser loginUser,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
                               @PathVariable Long id) {
        idempotencyService.execute(idemKey, loginUser.userId(), "POST /bookings/" + id + "/cancel",
                Map.of("id", id), Void.class, () -> {
                    bookingService.cancel(loginUser, id);
                    return null;
                });
        return Result.ok();
    }

    @PostMapping("/{id}/reschedule")
    public Result<RescheduleVO> reschedule(@AuthenticationPrincipal LoginUser loginUser,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
                                           @PathVariable Long id, @Valid @RequestBody HoldRequest request) {
        RescheduleVO rescheduleVO = idempotencyService.execute(idemKey, loginUser.userId(),
                "POST /bookings/" + id + "/reschedule", request, RescheduleVO.class,
                () -> bookingService.reschedule(loginUser, id, request));
        return Result.ok(rescheduleVO);
    }

    @GetMapping("/{id}/checkin-token")
    public Result<CheckinTokenVO> checkinToken(@AuthenticationPrincipal LoginUser loginUser,
                                               @PathVariable Long id) {
        return Result.ok(bookingService.checkinToken(loginUser, id));
    }
}
