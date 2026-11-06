package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wallet_account")
public class WalletAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 余额（含充值赠送，不退现） */
    private Long balanceCents;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
