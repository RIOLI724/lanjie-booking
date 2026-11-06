package com.lanjie.booking.controller;

import com.lanjie.booking.common.Result;
import com.lanjie.booking.service.AdminAnalyticsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 管理端经营分析：场地 × 时段占用热力。 */
@RestController
@RequestMapping("/api/v1/admin/analytics")
@PreAuthorize("hasAnyRole('ADMIN', 'FRONT_DESK')")
public class AdminAnalyticsController {

    private final AdminAnalyticsService analyticsService;

    public AdminAnalyticsController(AdminAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/heatmap")
    public Result<Map<String, Object>> heatmap(@RequestParam(defaultValue = "28") int days) {
        return Result.ok(analyticsService.heatmap(days));
    }
}
