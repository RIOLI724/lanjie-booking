package com.lanjie.booking.controller;

import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.common.Result;
import com.lanjie.booking.entity.CoachScheduleRule;
import com.lanjie.booking.entity.CoachService;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.service.AdminAuditService;
import com.lanjie.booking.service.AdminCoachOpsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@PreAuthorize("hasAnyRole('ADMIN', 'FRONT_DESK')")
public class AdminCoachController {

    private final AdminCoachOpsService opsService;
    private final AdminAuditService auditService;

    public AdminCoachController(AdminCoachOpsService opsService, AdminAuditService auditService) {
        this.opsService = opsService;
        this.auditService = auditService;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(opsService.coaches());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public Result<Long> create(@AuthenticationPrincipal LoginUser actor,
                               @Valid @RequestBody CreateCoachRequest request) {
        Long coachId = opsService.createCoach(request.getPhone(), request.getPassword(),
                request.getDisplayName(), request.getSpecialties(), request.getBasePriceCents(),
                request.getBio(), request.getCommissionRate());
        auditService.record(actor.userId(), "COACH_CREATE", "COACH", coachId,
                "新增教练 " + request.getDisplayName() + "（" + request.getPhone() + "）");
        return Result.ok(coachId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @AuthenticationPrincipal LoginUser actor,
                               @RequestBody UpdateCoachRequest request) {
        opsService.updateCoach(id, request.getDisplayName(), request.getSpecialties(),
                request.getBasePriceCents(), request.getBio(), request.getCommissionRate(),
                request.getStatus());
        auditService.record(actor.userId(), "COACH_UPDATE", "COACH", id,
                "编辑教练资料" + (request.getStatus() == null ? "" : "，状态 " + request.getStatus())
                        + (request.getBasePriceCents() == null ? "" : "，课时价 " + request.getBasePriceCents() + " 分"));
        return Result.ok();
    }

    @GetMapping("/{id}/schedule")
    public Result<Map<String, Object>> schedule(@PathVariable Long id) {
        return Result.ok(opsService.schedule(id));
    }

    @PutMapping("/{id}/schedule")
    public Result<Void> updateSchedule(@PathVariable Long id, @AuthenticationPrincipal LoginUser actor,
                                       @RequestBody ScheduleRequest request) {
        List<CoachScheduleRule> rules = new ArrayList<>();
        for (RuleItem item : request.getRules()) {
            CoachScheduleRule rule = new CoachScheduleRule();
            rule.setWeekday(item.getWeekday());
            rule.setStartTime(item.getStartTime());
            rule.setEndTime(item.getEndTime());
            rules.add(rule);
        }
        opsService.updateSchedule(id, rules);
        auditService.record(actor.userId(), "COACH_SCHEDULE", "COACH", id,
                "更新周排班，共 " + rules.size() + " 条规则，重建未来档期");
        return Result.ok();
    }

    @PostMapping("/{id}/time-off")
    public Result<Map<String, Object>> timeOff(@PathVariable Long id,
                                               @AuthenticationPrincipal LoginUser actor,
                                               @RequestBody TimeOffRequest request) {
        if (request.isForce() && !"ADMIN".equals(actor.role())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "请假区间有已售课程，取消并退款需要店长权限");
        }
        Map<String, Object> data = opsService.timeOff(id, request.getStartAt(), request.getEndAt(),
                request.getReason(), request.isForce());
        if (!Boolean.TRUE.equals(data.get("blocked"))) {
            auditService.record(actor.userId(), "COACH_TIME_OFF", "COACH", id,
                    "请假 " + request.getStartAt() + " 至 " + request.getEndAt()
                            + "，原因：" + request.getReason());
        }
        return Result.ok(data);
    }

    @DeleteMapping("/{id}/time-offs/{timeOffId}")
    public Result<Void> cancelTimeOff(@PathVariable Long id, @PathVariable Long timeOffId,
                                      @AuthenticationPrincipal LoginUser actor) {
        opsService.cancelTimeOff(id, timeOffId);
        auditService.record(actor.userId(), "COACH_TIME_OFF_CANCEL", "COACH", id,
                "撤销请假 " + timeOffId);
        return Result.ok();
    }

    /** 某天档期（团课排班选可用的教练时段） */
    @GetMapping("/{id}/slots")
    public Result<List<Map<String, Object>>> slots(@PathVariable Long id,
                                                   @RequestParam(required = false) String date) {
        LocalDate day;
        try {
            day = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        } catch (java.time.format.DateTimeParseException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "日期格式应为 yyyy-MM-dd");
        }
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
