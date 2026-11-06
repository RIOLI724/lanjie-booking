package com.lanjie.booking.vo;

public record WalletTxnVO(String type, long amountCents, long balanceAfterCents, String bizType, String createdAt) {
}
