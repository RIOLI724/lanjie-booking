package com.lanjie.booking.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class HoldRequest {

    @NotEmpty(message = "请选择时段")
    @Size(max = 4, message = "一次最多连选 4 格")
    private List<Long> slotIds;

    @Size(max = 20, message = "联系人姓名过长")
    private String contactName;

    @Size(max = 20, message = "联系电话过长")
    private String contactPhone;
}
