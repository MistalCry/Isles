package com.isles.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.channel.entity.JoinRequest;
import org.apache.ibatis.annotations.Mapper;

/**
 * 加入申请数据访问。
 */
@Mapper
public interface JoinRequestMapper extends BaseMapper<JoinRequest> {
}
