package com.lanjie.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanjie.booking.entity.CoachSlot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CoachSlotMapper extends BaseMapper<CoachSlot> {

    @Update("<script>"
            + "UPDATE coach_slot SET status = 'HELD', hold_token = #{token}, hold_expires_at = #{expiresAt}, "
            + "owner_type = 'BOOKING', owner_id = #{bookingId}, version = version + 1 "
            + "WHERE status = 'AVAILABLE' AND id IN "
            + "<foreach collection='slotIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int holdSlots(@Param("slotIds") List<Long> slotIds, @Param("token") String token,
                  @Param("expiresAt") LocalDateTime expiresAt, @Param("bookingId") Long bookingId);

    @Update("<script>"
            + "UPDATE coach_slot SET status = 'AVAILABLE', hold_token = NULL, hold_expires_at = NULL, "
            + "owner_type = NULL, owner_id = NULL, version = version + 1 "
            + "WHERE status = 'HELD' AND hold_expires_at &lt; #{now} AND id IN "
            + "<foreach collection='slotIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int reclaimExpired(@Param("slotIds") List<Long> slotIds, @Param("now") LocalDateTime now);

    @Update("UPDATE coach_slot SET status = 'AVAILABLE', hold_token = NULL, hold_expires_at = NULL, "
            + "owner_type = NULL, owner_id = NULL, version = version + 1 "
            + "WHERE owner_type = 'BOOKING' AND owner_id = #{bookingId} AND status = 'HELD'")
    int releaseByBooking(@Param("bookingId") Long bookingId);

    @Update("UPDATE coach_slot SET status = 'AVAILABLE', hold_token = NULL, hold_expires_at = NULL, "
            + "owner_type = NULL, owner_id = NULL, version = version + 1 "
            + "WHERE owner_type = 'BOOKING' AND owner_id = #{bookingId} AND status = 'BOOKED'")
    int releaseBookedByBooking(@Param("bookingId") Long bookingId);

    @Update("UPDATE coach_slot SET status = 'BOOKED', version = version + 1 "
            + "WHERE owner_type = 'BOOKING' AND owner_id = #{bookingId} AND status = 'HELD'")
    int confirmByBooking(@Param("bookingId") Long bookingId);

    @Update("UPDATE coach_slot SET status = 'AVAILABLE', hold_token = NULL, hold_expires_at = NULL, "
            + "owner_type = NULL, owner_id = NULL, version = version + 1 "
            + "WHERE status = 'HELD' AND hold_expires_at < #{now}")
    int releaseExpired(@Param("now") LocalDateTime now);

    /** 教练请假：区间内未售格子关闭 */
    @Update("UPDATE coach_slot SET status = 'CLOSED', version = version + 1 "
            + "WHERE coach_id = #{coachId} AND status = 'AVAILABLE' AND start_at >= #{startAt} AND end_at <= #{endAt}")
    int closeRange(@Param("coachId") Long coachId, @Param("startAt") LocalDateTime startAt,
                   @Param("endAt") LocalDateTime endAt);

    /** 取消请假：区间内 CLOSED 且无主的格子重开 */
    @Update("UPDATE coach_slot SET status = 'AVAILABLE', version = version + 1 "
            + "WHERE coach_id = #{coachId} AND status = 'CLOSED' AND owner_id IS NULL "
            + "AND start_at >= #{startAt} AND end_at <= #{endAt}")
    int reopenRange(@Param("coachId") Long coachId, @Param("startAt") LocalDateTime startAt,
                    @Param("endAt") LocalDateTime endAt);
}
