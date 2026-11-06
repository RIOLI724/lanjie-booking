package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("coach_time_off")
public class CoachTimeOff {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long coachId;

    private LocalDateTime startAt;

    private LocalDateTime endAt;

    private String reason;

    private LocalDateTime createdAt;
}
