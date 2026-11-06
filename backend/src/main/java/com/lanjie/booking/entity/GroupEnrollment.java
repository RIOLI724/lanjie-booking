package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lanjie.booking.enums.GroupEnrollmentStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("group_enrollment")
public class GroupEnrollment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long classId;

    private Long userId;

    private Long bookingId;

    private GroupEnrollmentStatus status;

    private LocalDateTime createdAt;
}
