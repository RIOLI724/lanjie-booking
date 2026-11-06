package com.lanjie.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanjie.booking.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
