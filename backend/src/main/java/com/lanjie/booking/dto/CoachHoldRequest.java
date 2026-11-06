package com.lanjie.booking.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CoachHoldRequest {

    @NotNull(message = "请选择教练时段")
    private Long coachSlotId;

    @NotNull(message = "请选择教练服务")
    private Long serviceId;

    /** 服务含场地时必填：同时段的场地格 */
    private Long courtSlotId;

    /** 可选：用课包核销 */
    private Long packageId;

    private String contactName;

    private String contactPhone;
}
