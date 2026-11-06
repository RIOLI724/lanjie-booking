package com.lanjie.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanjie.booking.entity.UserCoupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /** 用券：仅未使用且未过期可占用（防并发一券多用） */
    @Update("UPDATE user_coupon SET status = 'USED', used_at = NOW(), booking_id = #{bookingId} "
            + "WHERE id = #{id} AND user_id = #{userId} AND status = 'UNUSED' AND expires_at > NOW()")
    int markUsed(@Param("id") Long id, @Param("userId") Long userId, @Param("bookingId") Long bookingId);

    /** 全退返券 */
    @Update("UPDATE user_coupon SET status = 'UNUSED', used_at = NULL, booking_id = NULL "
            + "WHERE id = #{id} AND status = 'USED'")
    int restore(@Param("id") Long id);

    @Update("UPDATE coupon_template SET issued_quantity = issued_quantity + 1 "
            + "WHERE id = #{id} AND (total_quantity = 0 OR issued_quantity < total_quantity)")
    int incrIssued(@Param("id") Long id);
}
