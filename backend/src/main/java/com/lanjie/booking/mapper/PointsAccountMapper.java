package com.lanjie.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanjie.booking.entity.PointsAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PointsAccountMapper extends BaseMapper<PointsAccount> {

    @Update("UPDATE points_account SET balance = balance - #{points}, total_spent = total_spent + #{points}, "
            + "version = version + 1 WHERE user_id = #{userId} AND balance >= #{points}")
    int spend(@Param("userId") Long userId, @Param("points") int points);

    @Update("UPDATE points_account SET balance = balance + #{points}, total_earned = total_earned + #{points}, "
            + "version = version + 1 WHERE user_id = #{userId}")
    int earn(@Param("userId") Long userId, @Param("points") int points);

    @Update("UPDATE points_account SET balance = balance + #{points}, version = version + 1 "
            + "WHERE user_id = #{userId}")
    int restore(@Param("userId") Long userId, @Param("points") int points);
}
