package com.isles.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.channel.entity.Role;
import org.apache.ibatis.annotations.Mapper;

/**
 * 身份组数据访问。
 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {
}
