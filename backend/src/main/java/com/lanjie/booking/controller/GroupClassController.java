package com.lanjie.booking.controller;

import com.lanjie.booking.common.Result;
import com.lanjie.booking.entity.GroupEnrollment;
import com.lanjie.booking.security.LoginUser;
import com.lanjie.booking.service.GroupClassService;
import com.lanjie.booking.vo.GroupClassVO;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/group-classes")
public class GroupClassController {

    private final GroupClassService groupClassService;

    public GroupClassController(GroupClassService groupClassService) {
        this.groupClassService = groupClassService;
    }

    @GetMapping
    public Result<List<GroupClassVO>> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.ok(groupClassService.list(date));
    }

    @GetMapping("/{id}")
    public Result<GroupClassVO> detail(@PathVariable Long id) {
        return Result.ok(groupClassService.detail(id));
    }

    /** 报名：有空位建待支付订单；满员转候补 */
    @PostMapping("/{id}/enroll")
    public Result<GroupEnrollment> enroll(@AuthenticationPrincipal LoginUser loginUser, @PathVariable Long id) {
        return Result.ok(groupClassService.enroll(loginUser, id));
    }

    /** 取消报名/放弃候补 */
    @DeleteMapping("/{id}/enroll")
    public Result<Void> cancelEnroll(@AuthenticationPrincipal LoginUser loginUser, @PathVariable Long id) {
        groupClassService.cancelByUser(loginUser, id);
        return Result.ok();
    }
}
