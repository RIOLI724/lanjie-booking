package com.lanjie.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreatePickupRequest {

    @NotNull(message = "请选择场地时段")
    private Long slotId;

    /** 3v3 / 4v4 / 5v5 */
    @NotBlank(message = "请选择赛制")
    private String format;

    @Size(max = 50, message = "备注过长")
    private String note;
}
