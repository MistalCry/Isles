package com.isles.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表数据访问（常规 CRUD 交给 MyBatis-Plus BaseMapper，复杂查询写参数化 SQL）。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
