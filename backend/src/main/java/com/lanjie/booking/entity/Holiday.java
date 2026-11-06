package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lanjie.booking.enums.HolidayDateType;
import lombok.Data;

import java.time.LocalDate;

/** 节假日/调休：HOLIDAY=休（按节假日价），WORKDAY=班（按工作日价） */
@Data
@TableName("holiday")
public class Holiday {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate holidayDate;

    private HolidayDateType dateType;

    private String name;
}
