package com.lanjie.booking.controller;

import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.common.Result;
import com.lanjie.booking.service.VenueQueryService;
import com.lanjie.booking.vo.CalendarDayVO;
import com.lanjie.booking.vo.SlotVO;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class SlotController {

    private final VenueQueryService venueQueryService;

    public SlotController(VenueQueryService venueQueryService) {
        this.venueQueryService = venueQueryService;
    }

    @GetMapping("/slots")
    public Result<List<SlotVO>> slots(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.ok(venueQueryService.slotsOfDate(date));
    }

    @GetMapping("/slots/calendar")
    public Result<List<CalendarDayVO>> calendar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now();
        LocalDate end = to != null ? to : start.plusDays(6);
        if (start.isAfter(end) || start.plusDays(30).isBefore(end)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "日期范围不正确");
        }
        return Result.ok(venueQueryService.calendar(start, end));
    }
}
