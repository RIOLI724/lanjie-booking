package com.lanjie.booking.controller;

import com.lanjie.booking.common.Result;
import com.lanjie.booking.entity.GroupClass;
import com.lanjie.booking.service.GroupClassService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/** 管理端：团课排班（W11 后台页面接入） */
@RestController
@RequestMapping("/api/v1/admin/group-classes")
@PreAuthorize("hasRole('ADMIN')")
public class AdminGroupClassController {

    private final GroupClassService groupClassService;

    public AdminGroupClassController(GroupClassService groupClassService) {
        this.groupClassService = groupClassService;
    }

    @PostMapping
    public Result<GroupClass> create(@Valid @RequestBody CreateGroupClassRequest request) {
        return Result.ok(groupClassService.createClass(request.getCoachId(), request.getServiceId(),
                request.getStartAt(), request.getCourtId()));
    }

    @Data
    public static class CreateGroupClassRequest {
        @NotNull(message = "教练不能为空")
        private Long coachId;
        @NotNull(message = "服务不能为空")
        private Long serviceId;
        @NotNull(message = "开始时间不能为空")
        private LocalDateTime startAt;
        @NotNull(message = "场地不能为空")
        private Long courtId;
    }
}
