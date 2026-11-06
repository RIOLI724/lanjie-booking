package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lanjie.booking.enums.CoachServiceType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("coach_service")
public class CoachService {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long coachId;

    private CoachServiceType type;

    private String title;

    private Integer durationMin;

    private Integer priceCents;

    private Boolean includesCourt;

    private Integer minStudents;

    private Integer maxStudents;

    private String status;

    private LocalDateTime createdAt;
}
