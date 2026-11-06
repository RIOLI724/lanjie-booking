package com.lanjie.booking.controller;

import com.lanjie.booking.common.Result;
import com.lanjie.booking.service.VenueQueryService;
import com.lanjie.booking.vo.CourtVO;
import com.lanjie.booking.vo.VenueVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class VenueController {

    private final VenueQueryService venueQueryService;

    public VenueController(VenueQueryService venueQueryService) {
        this.venueQueryService = venueQueryService;
    }

    @GetMapping("/venue")
    public Result<VenueVO> venue() {
        return Result.ok(venueQueryService.venue());
    }

    @GetMapping("/courts")
    public Result<List<CourtVO>> courts() {
        return Result.ok(venueQueryService.courts());
    }
}
