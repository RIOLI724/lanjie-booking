package com.lanjie.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanjie.booking.entity.IdempotencyKey;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IdempotencyKeyMapper extends BaseMapper<IdempotencyKey> {
}
