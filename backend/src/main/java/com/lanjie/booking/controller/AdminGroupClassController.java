package com.lanjie.booking.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.common.Result;
import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.entity.Coach;
import com.lanjie.booking.entity.GroupClass;
import com.lanjie.booking.entity.GroupEnrollment;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.enums.GroupClassStatus;
import com.lanjie.booking.mapper.BookingMapper;
import com.lanjie.booking.mapper.CoachMapper;
import com.lanjie.booking.mapper.GroupClassMapper;
import com.lanjie.booking.mapper.GroupEnrollmentMapper;
import com.lanjie.booking.mapper.UserMapper;
import com.lanjie.booking.service.GroupClassService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 管理端：团课排班、报名名单、取消整班（全员全退）。 */
@RestController
@RequestMapping("/api/v1/admin/group-classes")
@PreAuthorize("hasRole('ADMIN')")
public class AdminGroupClassController {

    private final GroupClassService groupClassService;
    private final GroupClassMapper classMapper;
    private final GroupEnrollmentMapper enrollmentMapper;
    private final CoachMapper coachMapper;
    private final UserMapper userMapper;
    private final BookingMapper bookingMapper;

    public AdminGroupClassController(GroupClassService groupClassService, GroupClassMapper classMapper,
                                     GroupEnrollmentMapper enrollmentMapper, CoachMapper coachMapper,
                                     UserMapper userMapper, BookingMapper bookingMapper) {
        this.groupClassService = groupClassService;
        this.classMapper = classMapper;
        this.enrollmentMapper = enrollmentMapper;
        this.coachMapper = coachMapper;
        this.userMapper = userMapper;
        this.bookingMapper = bookingMapper;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) String date) {
        LocalDate day = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        List<GroupClass> classes = classMapper.selectList(new LambdaQueryWrapper<GroupClass>()
                .apply("DATE(start_at) = {0}", day)
                .orderByAsc(GroupClass::getStartAt));
        Map<Long, Coach> coaches = coachMapper.selectList(null).stream()
                .collect(Collectors.toMap(Coach::getId, c -> c));
        List<Map<String, Object>> result = new ArrayList<>();
        for (GroupClass clazz : classes) {
            Coach coach = coaches.get(clazz.getCoachId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", clazz.getId());
            row.put("title", clazz.getTitle());
            row.put("coachId", clazz.getCoachId());
            row.put("coachName", coach != null ? coach.getDisplayName() : "");
            row.put("startAt", clazz.getStartAt());
            row.put("endAt", clazz.getEndAt());
            row.put("minStudents", clazz.getMinStudents());
            row.put("maxStudents", clazz.getMaxStudents());
            row.put("priceCents", clazz.getPriceCents());
            row.put("enrolledCount", clazz.getEnrolledCount());
            row.put("status", clazz.getStatus());
            result.add(row);
        }
        return Result.ok(result);
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        GroupClass clazz = classMapper.selectById(id);
        if (clazz == null) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND, "团课不存在");
        }
        Coach coach = coachMapper.selectById(clazz.getCoachId());
        List<GroupEnrollment> enrollments = enrollmentMapper.selectList(
                new LambdaQueryWrapper<GroupEnrollment>()
                        .eq(GroupEnrollment::getClassId, id)
                        .orderByAsc(GroupEnrollment::getCreatedAt));
        Map<Long, User> users = enrollments.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(enrollments.stream().map(GroupEnrollment::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, u -> u));

        List<Map<String, Object>> roster = new ArrayList<>();
        for (GroupEnrollment enrollment : enrollments) {
            User user = users.get(enrollment.getUserId());
            Booking booking = enrollment.getBookingId() != null
                    ? bookingMapper.selectById(enrollment.getBookingId()) : null;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("enrollmentId", enrollment.getId());
            row.put("userId", enrollment.getUserId());
            row.put("userName", user != null ? user.getNickname() : "");
            row.put("userPhone", user != null ? user.getPhone() : "");
            row.put("status", enrollment.getStatus().name());
            row.put("bookingId", enrollment.getBookingId());
            row.put("bookingStatus", booking != null ? booking.getStatus().name() : "");
            row.put("paidCents", booking != null ? booking.getPaidCents() : 0);
            row.put("createdAt", enrollment.getCreatedAt());
            roster.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("groupClass", clazz);
        data.put("coachName", coach != null ? coach.getDisplayName() : "");
        data.put("roster", roster);
        return Result.ok(data);
    }

    @PostMapping
    public Result<GroupClass> create(@Valid @RequestBody CreateGroupClassRequest request) {
        return Result.ok(groupClassService.createClass(request.getCoachId(), request.getServiceId(),
                request.getStartAt(), request.getCourtId()));
    }

    /** 取消整班：已报名全员全退，释放教练档期与场地 */
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id,
                               @RequestBody(required = false) CancelRequest request) {
        GroupClass clazz = classMapper.selectById(id);
        if (clazz == null) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND, "团课不存在");
        }
        if (GroupClassStatus.CANCELLED.name().equals(clazz.getStatus())
                || GroupClassStatus.COMPLETED.name().equals(clazz.getStatus())) {
            throw new BusinessException(ErrorCode.BOOKING_STATUS_INVALID, "该团课已结束或已取消");
        }
        String reason = request == null || request.getReason() == null || request.getReason().isBlank()
                ? "场馆原因取消团课" : request.getReason();
        groupClassService.cancelClass(clazz, reason);
        return Result.ok();
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

    @Data
    public static class CancelRequest {
        private String reason;
    }
}
