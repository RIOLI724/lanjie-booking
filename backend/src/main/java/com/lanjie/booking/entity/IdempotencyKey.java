package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("idempotency_key")
public class IdempotencyKey {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String idemKey;

    private Long userId;

    private String endpoint;

    private String requestHash;

    private String responseJson;

    private LocalDateTime createdAt;
}
