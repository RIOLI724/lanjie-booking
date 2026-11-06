package com.lanjie.booking.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanjie.booking.entity.Booking;
import com.lanjie.booking.entity.NoShowRecord;
import com.lanjie.booking.entity.UserBan;
import com.lanjie.booking.mapper.BookingMapper;
import com.lanjie.booking.mapper.NoShowRecordMapper;
import com.lanjie.booking.mapper.UserBanMapper;
import com.lanjie.booking.service.BookingAuditService;
import com.lanjie.booking.service.PointsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 爽约与完成（每分钟）：
 * ① 已确认未核销、结束后 30 分钟 → 记爽约；7 天内 3 次自动禁订 7 天；
 * ② 已核销且场次结束 → 已完成。
 * 全部条件更新，天然幂等。
 */
@Slf4j
@Component
public class NoShowScheduler {

    private static final int NO_SHOW_STRIKE_LIMIT = 3;
    private static final int BAN_DAYS = 7;

    private final BookingMapper bookingMapper;
    private final NoShowRecordMapper noShowRecordMapper;
    private final UserBanMapper userBanMapper;
    private final BookingAuditService audit;
    private final PointsService pointsService;

    public NoShowScheduler(BookingMapper bookingMapper, NoShowRecordMapper noShowRecordMapper,
                           UserBanMapper userBanMapper, BookingAuditService audit,
                           PointsService pointsService) {
        this.bookingMapper = bookingMapper;
        this.noShowRecordMapper = noShowRecordMapper;
        this.userBanMapper = userBanMapper;
        this.audit = audit;
        this.pointsService = pointsService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        markNoShows(now);
        markCompleted(now);
    }

    private void markNoShows(LocalDateTime now) {
        List<Booking> candidates = bookingMapper.selectNoShowCandidates(now.minusMinutes(30));
        for (Booking booking : candidates) {
            if (bookingMapper.markNoShow(booking.getId()) == 0) {
                continue;
            }
            try {
                NoShowRecord record = new NoShowRecord();
                record.setUserId(booking.getUserId());
                record.setBookingId(booking.getId());
                noShowRecordMapper.insert(record);
            } catch (DuplicateKeyException e) {
                // 已记过，跳过
            }
            audit.write(booking.getId(), "CONFIRMED", "NO_SHOW", "SYSTEM", "结束后 30 分钟未到场");
            checkAndBan(booking.getUserId(), now);
        }
    }

    private void checkAndBan(Long userId, LocalDateTime now) {
        long strikes = noShowRecordMapper.selectCount(new LambdaQueryWrapper<NoShowRecord>()
                .eq(NoShowRecord::getUserId, userId)
                .ge(NoShowRecord::getCreatedAt, now.minusDays(7)));
        if (strikes < NO_SHOW_STRIKE_LIMIT) {
            return;
        }
        long activeBan = userBanMapper.selectCount(new LambdaQueryWrapper<UserBan>()
                .eq(UserBan::getUserId, userId)
                .gt(UserBan::getEndAt, now));
        if (activeBan > 0) {
            return;
        }
        UserBan ban = new UserBan();
        ban.setUserId(userId);
        ban.setReason("7 天内爽约 " + strikes + " 次，暂停预订 " + BAN_DAYS + " 天");
        ban.setStartAt(now);
        ban.setEndAt(now.plusDays(BAN_DAYS));
        userBanMapper.insert(ban);
        log.warn("user banned for no-shows: user={} strikes={} until={}", userId, strikes, ban.getEndAt());
    }

    private void markCompleted(LocalDateTime now) {
        List<Booking> candidates = bookingMapper.selectCompleteCandidates(now);
        for (Booking booking : candidates) {
            if (bookingMapper.markCompleted(booking.getId()) > 0) {
                audit.write(booking.getId(), "CONFIRMED", "COMPLETED", "SYSTEM", "场次结束");
                pointsService.earnForBooking(booking);
            }
        }
    }
}
