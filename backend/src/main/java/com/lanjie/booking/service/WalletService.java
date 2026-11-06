package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.entity.WalletAccount;
import com.lanjie.booking.entity.WalletTxn;
import com.lanjie.booking.enums.WalletTxnType;
import com.lanjie.booking.mapper.WalletAccountMapper;
import com.lanjie.booking.mapper.WalletTxnMapper;
import com.lanjie.booking.vo.WalletTxnVO;
import com.lanjie.booking.vo.WalletVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 储值钱包：每笔变动 = 「FOR UPDATE 锁余额 + 条件扣减/入账 + 写流水」同一事务；
 * 流水 (user, type, biz_type, biz_id) 唯一，重复入账被库直接拒绝，不靠先查后写。
 */
@Slf4j
@Service
public class WalletService {

    private final WalletAccountMapper accountMapper;
    private final WalletTxnMapper txnMapper;

    public WalletService(WalletAccountMapper accountMapper, WalletTxnMapper txnMapper) {
        this.accountMapper = accountMapper;
        this.txnMapper = txnMapper;
    }

    /** 读取（不存在则创建默认账户），无锁，用于展示 */
    public WalletAccount walletOf(Long userId) {
        WalletAccount wallet = accountMapper.selectOne(new LambdaQueryWrapper<WalletAccount>()
                .eq(WalletAccount::getUserId, userId)
                .last("LIMIT 1"));
        if (wallet == null) {
            wallet = new WalletAccount();
            wallet.setUserId(userId);
            wallet.setBalanceCents(0L);
            wallet.setVersion(0);
            accountMapper.insert(wallet);
        }
        return wallet;
    }

    public WalletVO balanceOf(Long userId) {
        return new WalletVO(walletOf(userId).getBalanceCents());
    }

    public List<WalletTxnVO> transactions(Long userId) {
        return txnMapper.selectList(new LambdaQueryWrapper<WalletTxn>()
                .eq(WalletTxn::getUserId, userId)
                .orderByDesc(WalletTxn::getId)
                .last("LIMIT 50"))
                .stream()
                .map(t -> new WalletTxnVO(t.getType().name(), t.getAmountCents(),
                        t.getBalanceAfterCents(), t.getBizType(), t.getCreatedAt().toString()))
                .toList();
    }

    /**
     * 入账。同一业务重复调用（唯一键命中）返回 false，表示此前已入过账。
     */
    @Transactional
    public boolean credit(Long userId, long amount, WalletTxnType type, String bizType, Long bizId) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "入账金额必须为正");
        }
        WalletAccount wallet = lockedWallet(userId);
        wallet.setBalanceCents(wallet.getBalanceCents() + amount);
        accountMapper.updateById(wallet);
        return insertTxn(userId, type, amount, wallet.getBalanceCents(), bizType, bizId);
    }

    /** 扣减。余额不足直接失败，钱和状态都不动 */
    @Transactional
    public void debit(Long userId, long amount, WalletTxnType type, String bizType, Long bizId) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "扣减金额必须为正");
        }
        WalletAccount wallet = lockedWallet(userId);
        if (wallet.getBalanceCents() < amount) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }
        wallet.setBalanceCents(wallet.getBalanceCents() - amount);
        accountMapper.updateById(wallet);
        if (!insertTxn(userId, type, -amount, wallet.getBalanceCents(), bizType, bizId)) {
            log.warn("wallet debit txn duplicated, biz={}:{}", bizType, bizId);
        }
    }

    private WalletAccount lockedWallet(Long userId) {
        WalletAccount wallet = accountMapper.selectOne(new LambdaQueryWrapper<WalletAccount>()
                .eq(WalletAccount::getUserId, userId)
                .last("FOR UPDATE"));
        if (wallet == null) {
            wallet = new WalletAccount();
            wallet.setUserId(userId);
            wallet.setBalanceCents(0L);
            wallet.setVersion(0);
            accountMapper.insert(wallet);
        }
        return wallet;
    }

    private boolean insertTxn(Long userId, WalletTxnType type, long amount, long balanceAfter,
                              String bizType, Long bizId) {
        WalletTxn txn = new WalletTxn();
        txn.setUserId(userId);
        txn.setType(type);
        txn.setAmountCents(amount);
        txn.setBalanceAfterCents(balanceAfter);
        txn.setBizType(bizType);
        txn.setBizId(bizId);
        try {
            txnMapper.insert(txn);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
