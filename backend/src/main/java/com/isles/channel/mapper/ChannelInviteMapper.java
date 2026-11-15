package com.isles.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.channel.entity.ChannelInvite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

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

    /**
     * 邀请链接管理列表（ULID 游标分页）。
     *
     * <p>已撤销的链接不再返回：撤销是终态，管理端只需要可用的链接；
     * 撤销动作本身留有审计日志（{@code invite.revoke}）可追溯。
     */
    @Select("""
            <script>
            SELECT * FROM channel_invites
            WHERE channel_id = #{channelId} AND revoked_at IS NULL
            <if test="cursorId != null">AND id &lt; #{cursorId}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<ChannelInvite> selectManageList(@Param("channelId") String channelId,
                                         @Param("cursorId") String cursorId,
                                         @Param("limit") int limit);

    @Update("""
            UPDATE channel_invites SET revoked_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND revoked_at IS NULL
            """)
    int revoke(@Param("id") String id);
}
