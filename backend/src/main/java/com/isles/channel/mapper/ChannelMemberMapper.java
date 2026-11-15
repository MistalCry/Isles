package com.isles.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.channel.entity.ChannelMember;
import com.isles.channel.mapper.row.CommonMemberRow;
import com.isles.channel.mapper.row.MentionCandidateRow;
import com.isles.channel.mapper.row.RoleMemberCountRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 频道成员数据访问。
 */
@Mapper
public interface ChannelMemberMapper extends BaseMapper<ChannelMember> {

    /**
     * 成员管理列表：搜索用户名/昵称、按身份组过滤，ULID 游标分页。
     */
    @Select("""
            <script>
            SELECT m.* FROM channel_members m
            JOIN users u ON u.id = m.user_id
            WHERE m.channel_id = #{channelId}
              AND m.status IN ('ACTIVE', 'MUTED')
            <if test="pattern != null">AND (u.username LIKE #{pattern} OR u.nickname LIKE #{pattern})</if>
            <if test="roleId != null">AND m.role_id = #{roleId}</if>
            <if test="cursorId != null">AND m.id &lt; #{cursorId}</if>
            ORDER BY m.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<ChannelMember> selectManageList(@Param("channelId") String channelId,
                                         @Param("pattern") String pattern,
                                         @Param("roleId") String roleId,
                                         @Param("cursorId") String cursorId,
                                         @Param("limit") int limit);

    /**
     * 成员管理 - 偏移分页（page/size）。
     */
    @Select("""
            <script>
            SELECT m.* FROM channel_members m
            JOIN users u ON u.id = m.user_id
            WHERE m.channel_id = #{channelId}
              AND m.status IN ('ACTIVE', 'MUTED')
            <if test="pattern != null">AND (u.username LIKE #{pattern} OR u.nickname LIKE #{pattern})</if>
            <if test="roleId != null">AND m.role_id = #{roleId}</if>
            ORDER BY m.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ChannelMember> selectManageListOffset(@Param("channelId") String channelId,
                                                @Param("pattern") String pattern,
                                                @Param("roleId") String roleId,
                                                @Param("offset") int offset,
                                                @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM channel_members m
            JOIN users u ON u.id = m.user_id
            WHERE m.channel_id = #{channelId}
              AND m.status IN ('ACTIVE', 'MUTED')
            <if test="pattern != null">AND (u.username LIKE #{pattern} OR u.nickname LIKE #{pattern})</if>
            <if test="roleId != null">AND m.role_id = #{roleId}</if>
            </script>
            """)
    long countManageList(@Param("channelId") String channelId,
                         @Param("pattern") String pattern,
                         @Param("roleId") String roleId);

    /**
     * 各身份组成员数（成员分析）。
     */
    @Select("""
            SELECT role_id, COUNT(*) AS member_count FROM channel_members
            WHERE channel_id = #{channelId} AND status IN ('ACTIVE', 'MUTED')
            GROUP BY role_id
            """)
    List<RoleMemberCountRow> selectRoleMemberCounts(@Param("channelId") String channelId);

    /**
     * 推荐规则：候选频道与"我加入的频道"的共同成员数。
     */
    @Select("""
            <script>
            SELECT m.channel_id AS channel_id, COUNT(DISTINCT m.user_id) AS common_count
            FROM channel_members m
            JOIN channel_members mine ON mine.user_id = m.user_id
                AND mine.channel_id IN
                <foreach item="cid" collection="baseChannelIds" open="(" separator="," close=")">#{cid}</foreach>
                AND mine.status IN ('ACTIVE', 'MUTED')
            WHERE m.channel_id IN
                <foreach item="cid" collection="candidateIds" open="(" separator="," close=")">#{cid}</foreach>
              AND m.status IN ('ACTIVE', 'MUTED')
              AND m.user_id &lt;&gt; #{userId}
            GROUP BY m.channel_id
            </script>
            """)
    List<CommonMemberRow> countCommonMembers(@Param("candidateIds") List<String> candidateIds,
                                             @Param("baseChannelIds") List<String> baseChannelIds,
                                             @Param("userId") String userId);

    /**
     * @提及候选：频道内 ACTIVE 成员（含频道助手虚拟账号），按昵称/用户名模糊过滤。
     */
    @Select("""
            <script>
            SELECT u.id AS id, u.username AS username, u.nickname AS nickname, u.avatar_url AS avatarUrl
            FROM channel_members m
            JOIN users u ON u.id = m.user_id
            WHERE m.channel_id = #{channelId} AND m.status = 'ACTIVE'
              AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
            <if test="keyword != null and keyword != ''">
              AND (u.nickname LIKE CONCAT('%', #{keyword}, '%') OR u.username LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            ORDER BY m.joined_at ASC
            LIMIT #{limit}
            </script>
            """)
    List<MentionCandidateRow> selectMentionCandidates(@Param("channelId") String channelId,
                                                      @Param("keyword") String keyword,
                                                      @Param("limit") int limit);
}
