package com.isles.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.channel.entity.ChannelInvite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 邀请链接数据访问。
 */
@Mapper
public interface ChannelInviteMapper extends BaseMapper<ChannelInvite> {

    @Update("""
            UPDATE channel_invites
            SET used_count = used_count + 1
            WHERE id = #{id} AND (max_uses IS NULL OR used_count < max_uses)
            """)
    int incrementUsedCount(@Param("id") String id);
}
