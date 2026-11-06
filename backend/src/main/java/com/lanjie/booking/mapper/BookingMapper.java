package com.lanjie.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanjie.booking.entity.Booking;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface BookingMapper extends BaseMapper<Booking> {

    /** 日限 2 单：用户在指定日期的活跃场地订单数（教练课/团课/拼场不占额度） */
    @Select("SELECT COUNT(DISTINCT b.id) FROM booking b "
            + "JOIN booking_item i ON i.booking_id = b.id "
            + "WHERE b.user_id = #{userId} AND b.type = 'COURT' "
            + "AND b.status IN ('PENDING_PAYMENT', 'CONFIRMED') "
            + "AND i.start_at >= #{dayStart} AND i.start_at < #{dayEnd}")
    int countActiveCourtBookingsOnDate(@Param("userId") Long userId,
                                       @Param("dayStart") LocalDateTime dayStart,
                                       @Param("dayEnd") LocalDateTime dayEnd);

    /** 条件更新：仅待支付可确认（支付成功） */
    @Update("UPDATE booking SET status = 'CONFIRMED', paid_cents = payable_cents "
            + "WHERE id = #{id} AND status = 'PENDING_PAYMENT'")
    int markConfirmed(@Param("id") Long id);

    /** 条件更新：仅待支付可取消 */
    @Update("UPDATE booking SET status = 'CANCELLED', cancel_reason = #{reason} "
            + "WHERE id = #{id} AND status = 'PENDING_PAYMENT'")
    int markCancelled(@Param("id") Long id, @Param("reason") String reason);
}
