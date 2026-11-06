package com.lanjie.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanjie.booking.entity.CoachPackage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CoachPackageMapper extends BaseMapper<CoachPackage> {

    /** 消耗一节：仅生效中且有余量；条件更新防并发超用 */
    @Update("UPDATE coach_package SET used_sessions = used_sessions + 1, remaining_sessions = remaining_sessions - 1 "
            + "WHERE id = #{id} AND status = 'ACTIVE' AND remaining_sessions > 0 AND expires_at > NOW()")
    int consumeSession(@Param("id") Long id);

    /** 取消退回一节：曾消耗过才退回 */
    @Update("UPDATE coach_package SET used_sessions = used_sessions - 1, remaining_sessions = remaining_sessions + 1, "
            + "status = 'ACTIVE' WHERE id = #{id} AND used_sessions > 0 AND status IN ('ACTIVE', 'EXHAUSTED')")
    int restoreSession(@Param("id") Long id);

    /** 支付成功激活 */
    @Update("UPDATE coach_package SET status = 'ACTIVE', pay_order_id = #{payOrderId} "
            + "WHERE id = #{id} AND status = 'PENDING_PAYMENT'")
    int activate(@Param("id") Long id, @Param("payOrderId") Long payOrderId);
}
