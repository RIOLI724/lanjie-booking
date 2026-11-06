package com.lanjie.booking.controller;

import com.lanjie.booking.common.Result;
import com.lanjie.booking.dto.CreatePickupRequest;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.service.BookingService;
import com.lanjie.booking.service.IdempotencyService;
import com.lanjie.booking.service.PickupService;
import com.lanjie.booking.vo.HoldVO;
import com.lanjie.booking.vo.PickupGameVO;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/pickup-games")
public class PickupController {

    private final PickupService pickupService;
    private final BookingService bookingService;
    private final IdempotencyService idempotencyService;

    public PickupController(PickupService pickupService, BookingService bookingService,
                            IdempotencyService idempotencyService) {
        this.pickupService = pickupService;
        this.bookingService = bookingService;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping
    public Result<List<PickupGameVO>> list(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String format) {
        return Result.ok(pickupService.list(loginUser, date, format));
    }

    @GetMapping("/{id}")
    public Result<PickupGameVO> detail(@AuthenticationPrincipal LoginUser loginUser, @PathVariable Long id) {
        return Result.ok(pickupService.detail(loginUser, id));
    }

    @PostMapping
    public Result<HoldVO> create(@AuthenticationPrincipal LoginUser loginUser,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
                                 @Valid @RequestBody CreatePickupRequest request) {
        HoldVO holdVO = idempotencyService.execute(idemKey, loginUser.userId(), "POST /pickup-games",
                request, HoldVO.class, () -> pickupService.create(loginUser, request));
        return Result.ok(holdVO);
    }

    @PostMapping("/{id}/join")
    public Result<HoldVO> join(@AuthenticationPrincipal LoginUser loginUser,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
                               @PathVariable Long id) {
        HoldVO holdVO = idempotencyService.execute(idemKey, loginUser.userId(),
                "POST /pickup-games/" + id + "/join", id, HoldVO.class,
                () -> pickupService.join(loginUser, id));
        return Result.ok(holdVO);
    }

    @DeleteMapping("/{id}/join")
    public Result<Void> quit(@AuthenticationPrincipal LoginUser loginUser, @PathVariable Long id) {
        Long bookingId = pickupService.participantBookingId(loginUser.userId(), id);
        if (bookingId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未加入该拼场");
        }
        bookingService.cancel(loginUser, bookingId);
        return Result.ok();
    }
}
