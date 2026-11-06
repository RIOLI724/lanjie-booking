package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("group_class")
public class GroupClass {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long coachId;

    private Long serviceId;

    private Long coachSlotId;

    private Long courtSlotId;

    private String title;

    private LocalDateTime startAt;

    private LocalDateTime endAt;

    private Integer minStudents;

    private Integer maxStudents;

    private Integer priceCents;

    private Integer enrolledCount;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
