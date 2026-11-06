package com.lanjie.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanjie.booking.entity.PayOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PayOrderMapper extends BaseMapper<PayOrder> {

    /** 条件更新：仅 PENDING 可标记 PAID（回调幂等的最终防线） */
    @Update("UPDATE pay_order SET status = 'PAID', third_party_txn_id = #{txnId}, paid_at = #{paidAt} "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int markPaid(@Param("id") Long id, @Param("txnId") String txnId, @Param("paidAt") java.time.LocalDateTime paidAt);
}
