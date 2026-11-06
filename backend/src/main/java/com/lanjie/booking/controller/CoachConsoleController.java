package com.lanjie.booking.controller;

import com.lanjie.booking.common.Result;
import com.lanjie.booking.entity.CoachScheduleRule;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.service.CoachConsoleService;
import com.lanjie.booking.vo.CoachSlotVO;
import jakarta.validation.Valid;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** 教练端（手机端内）：我的档期 / 请假 / 课表 / 分成 */
@RestController
@RequestMapping("/api/v1/coach/me")
public class CoachConsoleController {

    private final CoachConsoleService coachConsoleService;

    public CoachConsoleController(CoachConsoleService coachConsoleService) {
        this.coachConsoleService = coachConsoleService;
    }

    @GetMapping("/schedule")
    public Result<List<CoachScheduleRule>> schedule(@AuthenticationPrincipal LoginUser loginUser) {
        return Result.ok(coachConsoleService.schedule(loginUser));
    }

    @PutMapping("/schedule")
    public Result<List<CoachScheduleRule>> updateSchedule(@AuthenticationPrincipal LoginUser loginUser,
                                                          @Valid @RequestBody ScheduleUpdateRequest request) {
        return Result.ok(coachConsoleService.updateSchedule(loginUser, request.toRules()));
    }

    @PostMapping("/time-offs")
    public Result<Integer> timeOff(@AuthenticationPrincipal LoginUser loginUser,
                                   @Valid @RequestBody TimeOffRequest request) {
        int sold = coachConsoleService.timeOff(loginUser, request.getStartAt(), request.getEndAt(),
                request.getReason());
        return Result.ok(sold);
    }

    @DeleteMapping("/time-offs/{id}")
    public Result<Void> cancelTimeOff(@AuthenticationPrincipal LoginUser loginUser, @PathVariable Long id) {
        coachConsoleService.cancelTimeOff(loginUser, id);
        return Result.ok();
    }

    @GetMapping("/lessons")
    public Result<List<CoachSlotVO>> lessons(@AuthenticationPrincipal LoginUser loginUser) {
        return Result.ok(coachConsoleService.lessons(loginUser));
    }

    @GetMapping("/earnings")
    public Result<Long> earnings(@AuthenticationPrincipal LoginUser loginUser) {
        return Result.ok(coachConsoleService.monthEarnings(loginUser));
    }

    @Data
    public static class ScheduleUpdateRequest {
        private List<RuleItem> rules;

        @Data
        public static class RuleItem {
            private Integer weekday;
            @org.springframework.format.annotation.DateTimeFormat(pattern = "HH:mm")
            private java.time.LocalTime startTime;
            @org.springframework.format.annotation.DateTimeFormat(pattern = "HH:mm")
            private java.time.LocalTime endTime;
        }

        List<CoachScheduleRule> toRules() {
            return rules.stream().map(r -> {
                CoachScheduleRule rule = new CoachScheduleRule();
                rule.setWeekday(r.getWeekday());
                rule.setStartTime(r.getStartTime());
                rule.setEndTime(r.getEndTime());
                return rule;
            }).toList();
        }
    }

    @Data
    public static class TimeOffRequest {
        @jakarta.validation.constraints.NotNull(message = "开始时间不能为空")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime startAt;
        @jakarta.validation.constraints.NotNull(message = "结束时间不能为空")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        private LocalDateTime endAt;
        private String reason;
    }
}
