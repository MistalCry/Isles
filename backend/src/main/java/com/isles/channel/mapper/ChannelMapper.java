package com.isles.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.channel.entity.Channel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 频道数据访问。
 */
@Mapper
public interface ChannelMapper extends BaseMapper<Channel> {

    /**
     * 我加入的频道列表（M1 不分页：单用户加入频道数量有限，侧边栏一次展示；上限由业务控制）。
     */
    @Select("""
            SELECT c.*, r.name AS role_name
            FROM channels c
            JOIN channel_members m ON m.channel_id = c.id
            LEFT JOIN roles r ON r.id = m.role_id
            WHERE m.user_id = #{userId}
              AND m.status = 'ACTIVE'
              AND c.status = 'ACTIVE'
              AND c.deleted_at IS NULL
            ORDER BY m.joined_at DESC
            """)
    List<Channel> selectJoinedChannels(@Param("userId") String userId);

    /**
     * 全站搜索可见频道（仅公开频道或公开可见频道，docs/07 §1、docs/03 FR-SRCH-09）。
     */
    @Select("""
            <script>
            SELECT * FROM channels
            WHERE status = 'ACTIVE' AND deleted_at IS NULL
              AND (type = 'PUBLIC' OR visibility = 'PUBLIC_VISIBLE')
              AND (name LIKE #{pattern} OR description LIKE #{pattern})
            <if test="cursorId != null">AND id &lt; #{cursorId}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Channel> searchVisible(@Param("pattern") String pattern,
                                @Param("cursorId") String cursorId,
                                @Param("limit") int limit);

    /**
     * 搜索频道 - 按成员数排序（偏移分页，docs/03 FR-SRCH-06）。
     */
    @Select("""
            SELECT * FROM channels
            WHERE status = 'ACTIVE' AND deleted_at IS NULL
              AND (type = 'PUBLIC' OR visibility = 'PUBLIC_VISIBLE')
              AND (name LIKE #{pattern} OR description LIKE #{pattern})
            ORDER BY member_count DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<Channel> searchVisibleByMembers(@Param("pattern") String pattern,
                                         @Param("offset") int offset,
                                         @Param("limit") int limit);

    /**
     * 搜索频道 - 按活跃度（帖子数）排序。
     */
    @Select("""
            SELECT * FROM channels
            WHERE status = 'ACTIVE' AND deleted_at IS NULL
              AND (type = 'PUBLIC' OR visibility = 'PUBLIC_VISIBLE')
              AND (name LIKE #{pattern} OR description LIKE #{pattern})
            ORDER BY post_count DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<Channel> searchVisibleByActive(@Param("pattern") String pattern,
                                        @Param("offset") int offset,
                                        @Param("limit") int limit);

    /**
     * 搜索频道 - 相关度（名称命中权重高于简介，docs/03 §7）。
     */
    @Select("""
            SELECT * FROM channels
            WHERE status = 'ACTIVE' AND deleted_at IS NULL
              AND (type = 'PUBLIC' OR visibility = 'PUBLIC_VISIBLE')
              AND (name LIKE #{pattern} OR description LIKE #{pattern})
            ORDER BY (name LIKE #{pattern}) DESC, member_count DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<Channel> searchVisibleByRelevance(@Param("pattern") String pattern,
                                           @Param("offset") int offset,
                                           @Param("limit") int limit);

    /**
     * 推荐候选频道：公开、活跃、且当前用户未加入（按帖子数/成员数排序）。
     */
    @Select("""
            SELECT * FROM channels c
            WHERE c.status = 'ACTIVE' AND c.deleted_at IS NULL AND c.type = 'PUBLIC'
              AND NOT EXISTS (
                  SELECT 1 FROM channel_members m
                  WHERE m.channel_id = c.id AND m.user_id = #{userId}
                    AND m.status IN ('ACTIVE', 'MUTED')
              )
            ORDER BY c.post_count DESC, c.member_count DESC, c.id DESC
            LIMIT #{limit}
            """)
    List<Channel> selectRecommendationCandidates(@Param("userId") String userId, @Param("limit") int limit);

    /**
     * 全部活跃频道 ID（统计任务遍历用）。
     */
    @Select("SELECT id FROM channels WHERE status = 'ACTIVE' AND deleted_at IS NULL")
    List<String> selectActiveChannelIds();

    /**
     * 原子更新成员数。
     */
    @Update("""
            UPDATE channels
            SET member_count = GREATEST(member_count + #{delta}, 0), updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    int addMemberCount(@Param("id") String id, @Param("delta") int delta);

    /**
     * 原子更新帖子数。
     */
    @Update("""
            UPDATE channels
            SET post_count = GREATEST(post_count + #{delta}, 0), updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    int addPostCount(@Param("id") String id, @Param("delta") int delta);
}
