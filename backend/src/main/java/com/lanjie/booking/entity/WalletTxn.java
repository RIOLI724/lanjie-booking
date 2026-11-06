package com.lanjie.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lanjie.booking.enums.WalletTxnType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 钱包流水：(user_id, type, biz_type, biz_id) 唯一，重复入账被库直接拒绝；
 * 带余额快照，逐笔可对账。
 */
@Data
@TableName("wallet_txn")
public class WalletTxn {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private WalletTxnType type;

    /** 正入负出 */
    private Long amountCents;

    private Long balanceAfterCents;

    private String bizType;

    private Long bizId;

    private LocalDateTime createdAt;
}
