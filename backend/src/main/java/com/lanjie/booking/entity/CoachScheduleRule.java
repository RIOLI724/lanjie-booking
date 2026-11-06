package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalTime;

@Data
@TableName("coach_schedule_rule")
public class CoachScheduleRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long coachId;

    /** 1=周一 … 7=周日 */
    private Integer weekday;

    private LocalTime startTime;

    private LocalTime endTime;

    private String status;
}
