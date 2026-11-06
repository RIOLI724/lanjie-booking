package com.lanjie.booking.controller;

import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.common.Result;
import com.lanjie.booking.service.AdminFinanceService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/** 管理端财务：营收趋势、收支流水、CSV 导出。 */
@RestController
@RequestMapping("/api/v1/admin/finance")
@PreAuthorize("hasRole('ADMIN')")
public class AdminFinanceController {

    private final AdminFinanceService financeService;

    public AdminFinanceController(AdminFinanceService financeService) {
        this.financeService = financeService;
    }

    @GetMapping("/summary")
    public Result<Map<String, Object>> summary(@RequestParam(defaultValue = "7") int days) {
        return Result.ok(financeService.summary(days));
    }

    @GetMapping("/transactions")
    public Result<List<Map<String, Object>>> transactions(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String direction) {
        LocalDate[] range = range(from, to);
        return Result.ok(financeService.transactions(range[0], range[1], direction));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to) {
        LocalDate[] range = range(from, to);
        byte[] content = financeService.exportCsv(range[0], range[1]).getBytes(StandardCharsets.UTF_8);
        String filename = "finance-" + range[0] + "-" + range[1] + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(content);
    }

    private LocalDate[] range(String from, String to) {
        try {
            LocalDate end = to == null || to.isBlank() ? LocalDate.now() : LocalDate.parse(to);
            LocalDate start = from == null || from.isBlank() ? end.minusDays(6) : LocalDate.parse(from);
            if (start.isAfter(end)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "开始日期不能晚于结束日期");
            }
            return new LocalDate[]{start, end};
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "日期格式应为 yyyy-MM-dd");
        }
    }
}
