package com.isles.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.channel.entity.ChannelMember;
import org.apache.ibatis.annotations.Mapper;

/**
 * 频道成员数据访问。
 */
@Mapper
public interface ChannelMemberMapper extends BaseMapper<ChannelMember> {
}
