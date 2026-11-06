package com.lanjie.booking.controller;

import com.lanjie.booking.common.Result;
import com.lanjie.booking.entity.CoachScheduleRule;
import com.lanjie.booking.entity.CoachService;
import com.lanjie.booking.service.AdminCoachOpsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 管理端：教练入驻、资料编辑、代排班、代请假。 */
@RestController
@RequestMapping("/api/v1/admin/coaches")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCoachController {

    private final AdminCoachOpsService opsService;

    public AdminCoachController(AdminCoachOpsService opsService) {
        this.opsService = opsService;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(opsService.coaches());
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody CreateCoachRequest request) {
        return Result.ok(opsService.createCoach(request.getPhone(), request.getPassword(),
                request.getDisplayName(), request.getSpecialties(), request.getBasePriceCents(),
                request.getBio(), request.getCommissionRate()));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody UpdateCoachRequest request) {
        opsService.updateCoach(id, request.getDisplayName(), request.getSpecialties(),
                request.getBasePriceCents(), request.getBio(), request.getCommissionRate(),
                request.getStatus());
        return Result.ok();
    }

    @GetMapping("/{id}/schedule")
    public Result<Map<String, Object>> schedule(@PathVariable Long id) {
        return Result.ok(opsService.schedule(id));
    }

    @PutMapping("/{id}/schedule")
    public Result<Void> updateSchedule(@PathVariable Long id, @RequestBody ScheduleRequest request) {
        List<CoachScheduleRule> rules = new ArrayList<>();
        for (RuleItem item : request.getRules()) {
            CoachScheduleRule rule = new CoachScheduleRule();
            rule.setWeekday(item.getWeekday());
            rule.setStartTime(item.getStartTime());
            rule.setEndTime(item.getEndTime());
            rules.add(rule);
        }
        opsService.updateSchedule(id, rules);
        return Result.ok();
    }

    @PostMapping("/{id}/time-off")
    public Result<Map<String, Object>> timeOff(@PathVariable Long id, @RequestBody TimeOffRequest request) {
        return Result.ok(opsService.timeOff(id, request.getStartAt(), request.getEndAt(),
                request.getReason(), request.isForce()));
    }

    @DeleteMapping("/{id}/time-offs/{timeOffId}")
    public Result<Void> cancelTimeOff(@PathVariable Long id, @PathVariable Long timeOffId) {
        opsService.cancelTimeOff(id, timeOffId);
        return Result.ok();
    }

    /** 某天档期（团课排班选可用的教练时段） */
    @GetMapping("/{id}/slots")
    public Result<List<Map<String, Object>>> slots(@PathVariable Long id,
                                                   @RequestParam(required = false) String date) {
        LocalDate day = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> available = opsService.upcomingSlots(id, day, 1).stream()
                .filter(row -> "AVAILABLE".equals(row.get("status")))
                .filter(row -> ((LocalDateTime) row.get("startAt")).isAfter(now))
                .toList();
        return Result.ok(available);
    }

    @GetMapping("/{id}/services")
    public Result<List<CoachService>> services(@PathVariable Long id) {
        return Result.ok(opsService.services(id));
    }

    @Data
    public static class CreateCoachRequest {
        @NotBlank(message = "手机号不能为空")
        private String phone;
        @NotBlank(message = "初始密码不能为空")
        private String password;
        @NotBlank(message = "教练姓名不能为空")
        private String displayName;
        private String specialties;
        @NotNull(message = "课时价不能为空")
        @Positive(message = "课时价必须大于 0")
        private Integer basePriceCents;
        private String bio;
        private BigDecimal commissionRate;
    }

    @Data
    public static class UpdateCoachRequest {
        private String displayName;
        private String specialties;
        private Integer basePriceCents;
        private String bio;
        private BigDecimal commissionRate;
        private String status;
    }

    @Data
    public static class ScheduleRequest {
        @NotNull(message = "排班规则不能为空")
        private List<RuleItem> rules;
    }

    @Data
    public static class RuleItem {
        @NotNull
        private Integer weekday;
        @NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
        private LocalTime startTime;
        @NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
        private LocalTime endTime;
    }

    @Data
    public static class TimeOffRequest {
        @NotNull(message = "开始时间不能为空")
        private LocalDateTime startAt;
        @NotNull(message = "结束时间不能为空")
        private LocalDateTime endAt;
        private String reason;
        private boolean force;
    }
}
