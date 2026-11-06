package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.entity.MemberLevel;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.mapper.MemberLevelMapper;
import com.lanjie.booking.mapper.UserMapper;
import com.lanjie.booking.vo.MemberVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 会员：四档（0/1000/3000/8000 累计充值 → 98/95/92 折），只升不降；
 * 折扣只作用于场地与私教（团课/拼场/课包不叠加）。
 */
@Slf4j
@Service
public class MembershipService {

    private final MemberLevelMapper levelMapper;
    private final UserMapper userMapper;
    private final PointsService pointsService;

    public MembershipService(MemberLevelMapper levelMapper, UserMapper userMapper, PointsService pointsService) {
        this.levelMapper = levelMapper;
        this.userMapper = userMapper;
        this.pointsService = pointsService;
    }

    public MemberLevel levelOf(User user) {
        if (user.getMemberLevelId() != null) {
            MemberLevel level = levelMapper.selectById(user.getMemberLevelId());
            if (level != null) {
                return level;
            }
        }
        return levelMapper.selectOne(new LambdaQueryWrapper<MemberLevel>()
                .orderByAsc(MemberLevel::getThresholdCents)
                .last("LIMIT 1"));
    }

    /** 充值成功：累计本金升级会员（只升不降） */
    @Transactional
    public void onRecharge(Long userId, long principalCents) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return;
        }
        long cumulative = (user.getCumulativeRechargeCents() == null ? 0 : user.getCumulativeRechargeCents())
                + principalCents;
        user.setCumulativeRechargeCents(cumulative);
        MemberLevel best = levelMapper.selectOne(new LambdaQueryWrapper<MemberLevel>()
                .le(MemberLevel::getThresholdCents, cumulative)
                .orderByDesc(MemberLevel::getThresholdCents)
                .last("LIMIT 1"));
        if (best != null && !best.getId().equals(user.getMemberLevelId())) {
            user.setMemberLevelId(best.getId());
            log.info("member upgraded: user={} level={}", userId, best.getName());
        }
        userMapper.updateById(user);
    }

    public MemberVO memberVO(Long userId) {
        User user = userMapper.selectById(userId);
        MemberLevel level = levelOf(user);
        return new MemberVO(level != null ? level.getName() : "普通",
                level != null ? level.getDiscountPct() : 100,
                level != null ? level.getPointsRate().doubleValue() : 1.0,
                user.getCumulativeRechargeCents() == null ? 0 : user.getCumulativeRechargeCents(),
                pointsService.balanceOf(userId));
    }

    public int discountPctFor(User user) {
        MemberLevel level = levelOf(user);
        return level != null ? level.getDiscountPct() : 100;
    }

    public List<MemberLevel> levels() {
        return levelMapper.selectList(new LambdaQueryWrapper<MemberLevel>()
                .orderByAsc(MemberLevel::getThresholdCents));
    }
}
