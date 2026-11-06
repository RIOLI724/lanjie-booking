package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;
import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.entity.MemberLevel;
import com.lanjie.booking.entity.PointsAccount;
import com.lanjie.booking.entity.PointsTransaction;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.enums.BookingStatus;
import com.lanjie.booking.mapper.MemberLevelMapper;
import com.lanjie.booking.mapper.PointsAccountMapper;
import com.lanjie.booking.mapper.PointsTransactionMapper;
import com.lanjie.booking.mapper.UserMapper;
import com.lanjie.booking.vo.PointsTxnVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 积分：完成订单后按实付 × 会员倍率发放；100 分 = 1 元，抵扣不超过应付 20% */
@Slf4j
@Service
public class PointsService {

    private final PointsAccountMapper accountMapper;
    private final PointsTransactionMapper txnMapper;
    private final UserMapper userMapper;
    private final MemberLevelMapper levelMapper;

    public PointsService(PointsAccountMapper accountMapper, PointsTransactionMapper txnMapper,
                         UserMapper userMapper, MemberLevelMapper levelMapper) {
        this.accountMapper = accountMapper;
        this.txnMapper = txnMapper;
        this.userMapper = userMapper;
        this.levelMapper = levelMapper;
    }

    public int balanceOf(Long userId) {
        PointsAccount account = accountMapper.selectOne(new LambdaQueryWrapper<PointsAccount>()
                .eq(PointsAccount::getUserId, userId)
                .last("LIMIT 1"));
        return account != null ? account.getBalance() : 0;
    }

    public List<PointsTxnVO> transactions(Long userId) {
        return txnMapper.selectList(new LambdaQueryWrapper<PointsTransaction>()
                        .eq(PointsTransaction::getUserId, userId)
                        .orderByDesc(PointsTransaction::getId)
                        .last("LIMIT 50"))
                .stream()
                .map(t -> new PointsTxnVO(t.getType(), t.getPoints(), t.getBalanceAfter(),
                        t.getBizType(), t.getCreatedAt().toString()))
                .toList();
    }

    @Transactional
    public void spend(Long userId, int points, Long bookingId) {
        if (points <= 0) {
            return;
        }
        ensureAccount(userId);
        if (accountMapper.spend(userId, points) == 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE, "积分不足");
        }
        record(userId, "SPEND", -points, "BOOKING", bookingId);
    }

    @Transactional
    public void restoreSpend(Long userId, int points, Long bookingId) {
        if (points <= 0) {
            return;
        }
        accountMapper.restore(userId, points);
        record(userId, "REFUND", points, "BOOKING", bookingId);
    }

    /** 订单完成：按实付（分）/100 × 会员倍率发积分 */
    @Transactional
    public void earnForBooking(Booking booking) {
        if (booking.getPaidCents() == null || booking.getPaidCents() <= 0) {
            return;
        }
        User user = userMapper.selectById(booking.getUserId());
        double rate = 1.0;
        if (user != null && user.getMemberLevelId() != null) {
            MemberLevel level = levelMapper.selectById(user.getMemberLevelId());
            if (level != null) {
                rate = level.getPointsRate().doubleValue();
            }
        }
        int points = (int) Math.floor(booking.getPaidCents() / 100.0 * rate);
        if (points <= 0) {
            return;
        }
        ensureAccount(booking.getUserId());
        accountMapper.earn(booking.getUserId(), points);
        record(booking.getUserId(), "EARN", points, "BOOKING", booking.getId());
        log.info("points earned: user={} booking={} points={}", booking.getUserId(), booking.getId(), points);
    }

    private void ensureAccount(Long userId) {
        PointsAccount account = accountMapper.selectOne(new LambdaQueryWrapper<PointsAccount>()
                .eq(PointsAccount::getUserId, userId)
                .last("LIMIT 1"));
        if (account == null) {
            account = new PointsAccount();
            account.setUserId(userId);
            account.setBalance(0);
            account.setTotalEarned(0);
            account.setTotalSpent(0);
            account.setVersion(0);
            try {
                accountMapper.insert(account);
            } catch (DuplicateKeyException ignored) {
                // 并发创建，忽略
            }
        }
    }

    private void record(Long userId, String type, int points, String bizType, Long bizId) {
        PointsTransaction txn = new PointsTransaction();
        txn.setUserId(userId);
        txn.setType(type);
        txn.setPoints(points);
        PointsAccount account = accountMapper.selectOne(new LambdaQueryWrapper<PointsAccount>()
                .eq(PointsAccount::getUserId, userId)
                .last("LIMIT 1"));
        txn.setBalanceAfter(account != null ? account.getBalance() : 0);
        txn.setBizType(bizType);
        txn.setBizId(bizId);
        try {
            txnMapper.insert(txn);
        } catch (DuplicateKeyException ignored) {
            // 同一业务同一类型只记一次
        }
    }
}
