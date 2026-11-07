package com.lanjie.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanjie.booking.entity.Slot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SlotMapper extends BaseMapper<Slot> {

    /** 条件更新锁位：只有全部仍是 AVAILABLE 才成功，返回受影响行数（防超订的最终防线） */
    @Update("<script>"
            + "UPDATE slot SET status = 'HELD', hold_token = #{token}, hold_expires_at = #{expiresAt}, "
            + "owner_type = 'BOOKING', owner_id = #{bookingId}, version = version + 1 "
            + "WHERE status = 'AVAILABLE' AND id IN "
            + "<foreach collection='slotIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int holdSlots(@Param("slotIds") List<Long> slotIds, @Param("token") String token,
                  @Param("expiresAt") LocalDateTime expiresAt, @Param("bookingId") Long bookingId);

    /** 先回收已过期但仍标 HELD 的格子（不等扫库兜底） */
    @Update("<script>"
            + "UPDATE slot SET status = 'AVAILABLE', hold_token = NULL, hold_expires_at = NULL, "
            + "owner_type = NULL, owner_id = NULL, version = version + 1 "
            + "WHERE status = 'HELD' AND hold_expires_at &lt; #{now} AND id IN "
            + "<foreach collection='slotIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int reclaimExpired(@Param("slotIds") List<Long> slotIds, @Param("now") LocalDateTime now);

    /** 订单取消/超时：释放该订单持有的格子 */
    @Update("UPDATE slot SET status = 'AVAILABLE', hold_token = NULL, hold_expires_at = NULL, "
            + "owner_type = NULL, owner_id = NULL, version = version + 1 "
            + "WHERE owner_type = 'BOOKING' AND owner_id = #{bookingId} AND status = 'HELD'")
    int releaseByBooking(@Param("bookingId") Long bookingId);

    /** 支付成功：HELD → BOOKED */
    @Update("UPDATE slot SET status = 'BOOKED', version = version + 1 "
            + "WHERE owner_type = 'BOOKING' AND owner_id = #{bookingId} AND status = 'HELD'")
    int confirmByBooking(@Param("bookingId") Long bookingId);

    /** 改期落地：旧格子（BOOKED）释放，不动 HELD 的新格子 */
    @Update("UPDATE slot SET status = 'AVAILABLE', hold_token = NULL, hold_expires_at = NULL, "
            + "owner_type = NULL, owner_id = NULL, version = version + 1 "
            + "WHERE owner_type = 'BOOKING' AND owner_id = #{bookingId} AND status = 'BOOKED'")
    int releaseBookedByBooking(@Param("bookingId") Long bookingId);

    /** 扫库兜底：释放所有过期 HELD 格子 */
    @Update("UPDATE slot SET status = 'AVAILABLE', hold_token = NULL, hold_expires_at = NULL, "
            + "owner_type = NULL, owner_id = NULL, version = version + 1 "
            + "WHERE status = 'HELD' AND hold_expires_at < #{now}")
    int releaseExpired(@Param("now") LocalDateTime now);


    /** 管理端关场：只有仍可订时才允许，防止与顾客锁位并发覆盖 */
    @Update("UPDATE slot SET status = 'CLOSED', hold_token = NULL, hold_expires_at = NULL, "
            + "owner_type = NULL, owner_id = NULL, version = version + 1 "
            + "WHERE id = #{id} AND status = 'AVAILABLE'")
    int closeIfAvailable(@Param("id") Long id);

    /** 管理端重新开放：只有已关且无主时才允许 */
    @Update("UPDATE slot SET status = 'AVAILABLE', version = version + 1 "
            + "WHERE id = #{id} AND status = 'CLOSED'")
    int reopenIfClosed(@Param("id") Long id);

    /** 管理端改价：只改仍可订的格子 */
    @Update("UPDATE slot SET price_cents = #{priceCents}, version = version + 1 "
            + "WHERE id = #{id} AND status = 'AVAILABLE'")
    int updatePriceIfAvailable(@Param("id") Long id, @Param("priceCents") Integer priceCents);
}
