package com.lanjie.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanjie.booking.entity.Booking;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface BookingMapper extends BaseMapper<Booking> {

    /** 日限 2 单：用户在指定日期的活跃场地订单数（教练课/团课/拼场不占额度；改期时排除本单） */
    @Select("<script>SELECT COUNT(DISTINCT b.id) FROM booking b "
            + "JOIN booking_item i ON i.booking_id = b.id "
            + "WHERE b.user_id = #{userId} AND b.type = 'COURT' "
            + "AND b.status IN ('PENDING_PAYMENT', 'CONFIRMED') "
            + "AND i.start_at &gt;= #{dayStart} AND i.start_at &lt; #{dayEnd}"
            + "<if test='excludeBookingId != null'> AND b.id != #{excludeBookingId}</if></script>")
    int countActiveCourtBookingsOnDate(@Param("userId") Long userId,
                                       @Param("dayStart") LocalDateTime dayStart,
                                       @Param("dayEnd") LocalDateTime dayEnd,
                                       @Param("excludeBookingId") Long excludeBookingId);

    /** 条件更新：仅待支付可确认（支付成功） */
    @Update("UPDATE booking SET status = 'CONFIRMED', paid_cents = payable_cents "
            + "WHERE id = #{id} AND status = 'PENDING_PAYMENT'")
    int markConfirmed(@Param("id") Long id);

    /** 条件更新：仅待支付可取消 */
    @Update("UPDATE booking SET status = 'CANCELLED', cancel_reason = #{reason} "
            + "WHERE id = #{id} AND status = 'PENDING_PAYMENT'")
    int markCancelled(@Param("id") Long id, @Param("reason") String reason);

    /** 条件更新：已确认可取消（走退款阶梯） */
    @Update("UPDATE booking SET status = 'CANCELLED', cancel_reason = #{reason} "
            + "WHERE id = #{id} AND status = 'CONFIRMED'")
    int markCancelledConfirmed(@Param("id") Long id, @Param("reason") String reason);

    /** 爽约候选：已确认未核销且场次结束超 30 分钟 */
    @Select("SELECT b.* FROM booking b WHERE b.type = 'COURT' AND b.status = 'CONFIRMED' "
            + "AND b.checked_in_at IS NULL AND EXISTS "
            + "(SELECT 1 FROM booking_item i WHERE i.booking_id = b.id AND i.end_at < #{threshold})")
    List<Booking> selectNoShowCandidates(@Param("threshold") LocalDateTime threshold);

    /** 完成候选：已核销且场次结束 */
    @Select("SELECT b.* FROM booking b WHERE b.type = 'COURT' AND b.status = 'CONFIRMED' "
            + "AND b.checked_in_at IS NOT NULL AND EXISTS "
            + "(SELECT 1 FROM booking_item i WHERE i.booking_id = b.id AND i.end_at < #{now})")
    List<Booking> selectCompleteCandidates(@Param("now") LocalDateTime now);

    /** 条件更新：已确认且未核销 → 爽约 */
    @Update("UPDATE booking SET status = 'NO_SHOW' "
            + "WHERE id = #{id} AND status = 'CONFIRMED' AND checked_in_at IS NULL")
    int markNoShow(@Param("id") Long id);

    /** 条件更新：已核销且场次结束 → 已完成 */
    @Update("UPDATE booking SET status = 'COMPLETED' WHERE id = #{id} AND status = 'CONFIRMED'")
    int markCompleted(@Param("id") Long id);

    /** 改期计数 +1，改期补差价入实付 */
    @Update("UPDATE booking SET reschedule_count = reschedule_count + 1, paid_cents = paid_cents + #{diffCents} "
            + "WHERE id = #{id}")
    int applyReschedule(@Param("id") Long id, @Param("diffCents") int diffCents);

    /** 仅改期计数 +1（差价 ≤ 0） */
    @Update("UPDATE booking SET reschedule_count = reschedule_count + 1 WHERE id = #{id}")
    int incrRescheduleCount(@Param("id") Long id);

    /** 核销：一次性码，核销成功立即作废 */
    @Update("UPDATE booking SET checked_in_at = #{checkedAt}, checkin_token = NULL "
            + "WHERE id = #{id} AND status = 'CONFIRMED' AND checkin_token = #{token}")
    int markCheckedIn(@Param("id") Long id, @Param("token") String token, @Param("checkedAt") LocalDateTime checkedAt);
}
