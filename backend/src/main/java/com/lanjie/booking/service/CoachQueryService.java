package com.lanjie.booking.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.entity.Coach;
import com.lanjie.booking.entity.CoachPackage;
import com.lanjie.booking.entity.CoachService;
import com.lanjie.booking.entity.CoachSlot;
import com.lanjie.booking.entity.User;
import com.lanjie.booking.enums.PackageStatus;
import com.lanjie.booking.mapper.CoachMapper;
import com.lanjie.booking.mapper.CoachPackageMapper;
import com.lanjie.booking.mapper.CoachServiceMapper;
import com.lanjie.booking.mapper.CoachSlotMapper;
import com.lanjie.booking.mapper.UserMapper;
import com.lanjie.booking.vo.CoachPackageVO;
import com.lanjie.booking.vo.CoachSlotVO;
import com.lanjie.booking.vo.CoachVO;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

import com.lanjie.booking.common.BusinessException;
import com.lanjie.booking.common.ErrorCode;

/** 教练查询（列表/详情/档期/我的课包） */
@Service
public class CoachQueryService {

    private final CoachMapper coachMapper;
    private final CoachSlotMapper coachSlotMapper;
    private final CoachServiceMapper coachServiceMapper;
    private final CoachPackageMapper coachPackageMapper;
    private final UserMapper userMapper;

    public CoachQueryService(CoachMapper coachMapper, CoachSlotMapper coachSlotMapper,
                             CoachServiceMapper coachServiceMapper, CoachPackageMapper coachPackageMapper,
                             UserMapper userMapper) {
        this.coachMapper = coachMapper;
        this.coachSlotMapper = coachSlotMapper;
        this.coachServiceMapper = coachServiceMapper;
        this.coachPackageMapper = coachPackageMapper;
        this.userMapper = userMapper;
    }

    public List<CoachVO> coaches() {
        return coachMapper.selectList(new LambdaQueryWrapper<Coach>()
                .eq(Coach::getStatus, "ACTIVE")
                .orderByAsc(Coach::getSortOrder))
                .stream().map(this::toVO).toList();
    }

    public CoachVO coach(Long id) {
        Coach coach = coachMapper.selectById(id);
        if (coach == null || !"ACTIVE".equals(coach.getStatus())) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_FOUND, "教练不存在");
        }
        return toVO(coach);
    }

    public List<CoachSlotVO> slots(Long coachId, LocalDate date) {
        coach(coachId);
        return coachSlotMapper.selectList(new LambdaQueryWrapper<CoachSlot>()
                .eq(CoachSlot::getCoachId, coachId)
                .apply("DATE(start_at) = {0}", date)
                .orderByAsc(CoachSlot::getStartAt))
                .stream()
                .map(s -> new CoachSlotVO(s.getId(), s.getStartAt().toString(),
                        s.getEndAt().toString(), s.getStatus().name()))
                .toList();
    }

    public List<CoachService> servicesOf(Long coachId) {
        return coachServiceMapper.selectList(new LambdaQueryWrapper<CoachService>()
                .eq(CoachService::getCoachId, coachId)
                .eq(CoachService::getStatus, "ACTIVE"));
    }

    public List<CoachPackageVO> myPackages(Long userId) {
        List<CoachPackage> packages = coachPackageMapper.selectList(new LambdaQueryWrapper<CoachPackage>()
                .eq(CoachPackage::getUserId, userId)
                .in(CoachPackage::getStatus, PackageStatus.ACTIVE, PackageStatus.EXHAUSTED)
                .orderByDesc(CoachPackage::getId));
        return packages.stream().map(p -> {
            Coach coach = coachMapper.selectById(p.getCoachId());
            CoachService service = coachServiceMapper.selectById(p.getServiceId());
            return new CoachPackageVO(p.getId(), coach != null ? coach.getDisplayName() : "",
                    service != null ? service.getTitle() : "", p.getTotalSessions(),
                    p.getUsedSessions(), p.getRemainingSessions(), p.getPriceCents(),
                    p.getExpiresAt().toString(), p.getStatus().name());
        }).toList();
    }

    public Coach coachByUserId(Long userId) {
        return coachMapper.selectOne(new LambdaQueryWrapper<Coach>()
                .eq(Coach::getUserId, userId)
                .last("LIMIT 1"));
    }

    public User userOf(Long userId) {
        return userMapper.selectById(userId);
    }

    private CoachVO toVO(Coach coach) {
        List<CoachService> services = servicesOf(coach.getId());
        List<CoachVO.ServiceVO> serviceVOs = services.stream()
                .map(s -> new CoachVO.ServiceVO(s.getId(), s.getType().name(), s.getTitle(),
                        s.getPriceCents(), s.getIncludesCourt(), s.getMinStudents(), s.getMaxStudents()))
                .toList();
        return new CoachVO(coach.getId(), coach.getDisplayName(), coach.getAvatarUrl(), coach.getBio(),
                coach.getSpecialties(), coach.getRating().doubleValue(), coach.getRatingCount(),
                coach.getBasePriceCents(), serviceVOs);
    }
}
