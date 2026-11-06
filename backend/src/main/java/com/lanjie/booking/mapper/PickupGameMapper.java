package com.lanjie.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanjie.booking.entity.PickupGame;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PickupGameMapper extends BaseMapper<PickupGame> {

    /** 占一个坑：未满且未取消才 +1 */
    @Update("UPDATE pickup_game SET joined_players = joined_players + 1 "
            + "WHERE id = #{id} AND joined_players < total_players AND status IN ('OPEN')")
    int joinOne(@Param("id") Long id);

    /** 退一个坑 */
    @Update("UPDATE pickup_game SET joined_players = joined_players - 1 "
            + "WHERE id = #{id} AND joined_players > 1 AND status IN ('OPEN', 'FULL')")
    int leaveOne(@Param("id") Long id);

    @Update("UPDATE pickup_game SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE pickup_game SET settled = 1 WHERE id = #{id} AND settled = 0")
    int markSettled(@Param("id") Long id);
}
