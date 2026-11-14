package com.isles.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.post.entity.Post;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 帖子数据访问：动态流 / 频道帖子流 / 搜索统一走参数化 SQL，游标分页。
 *
 * <p>热度公式（docs/03 §7）：{@code 点赞×3 + 评论×5 + 浏览×1}，M1 用 SQL 近似，不做时间衰减。
 */
@Mapper
public interface PostMapper extends BaseMapper<Post> {

    /**
     * 频道帖子流 - 最新（含精华筛选）。
     */
    @Select("""
            <script>
            SELECT * FROM posts
            WHERE channel_id = #{channelId} AND status = 'PUBLISHED' AND deleted_at IS NULL
            <if test="boardId != null">AND board_id = #{boardId}</if>
            <if test="essenceOnly">AND is_essence = 1</if>
            <if test="cursorId != null">AND id &lt; #{cursorId}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Post> selectChannelLatest(@Param("channelId") String channelId,
                                   @Param("boardId") String boardId,
                                   @Param("essenceOnly") boolean essenceOnly,
                                   @Param("cursorId") String cursorId,
                                   @Param("limit") int limit);

    /**
     * 频道帖子流 - 热门（热度游标）。
     */
    @Select("""
            <script>
            SELECT * FROM posts
            WHERE channel_id = #{channelId} AND status = 'PUBLISHED' AND deleted_at IS NULL
            <if test="boardId != null">AND board_id = #{boardId}</if>
            <if test="essenceOnly">AND is_essence = 1</if>
            <if test="cursorScore != null">
              AND ((view_count + like_count * 3 + comment_count * 5) &lt; #{cursorScore}
                   OR ((view_count + like_count * 3 + comment_count * 5) = #{cursorScore} AND id &lt; #{cursorId}))
            </if>
            ORDER BY (view_count + like_count * 3 + comment_count * 5) DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Post> selectChannelHot(@Param("channelId") String channelId,
                                @Param("boardId") String boardId,
                                @Param("essenceOnly") boolean essenceOnly,
                                @Param("cursorScore") Long cursorScore,
                                @Param("cursorId") String cursorId,
                                @Param("limit") int limit);

    /**
     * 已加入频道的动态 - 最新。
     */
    @Select("""
            <script>
            SELECT * FROM posts
            WHERE status = 'PUBLISHED' AND deleted_at IS NULL
              AND channel_id IN
              <foreach item="cid" collection="channelIds" open="(" separator="," close=")">#{cid}</foreach>
            <if test="cursorId != null">AND id &lt; #{cursorId}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Post> selectFeedLatest(@Param("channelIds") List<String> channelIds,
                                @Param("cursorId") String cursorId,
                                @Param("limit") int limit);

    /**
     * 已加入频道的动态 - 热门。
     */
    @Select("""
            <script>
            SELECT * FROM posts
            WHERE status = 'PUBLISHED' AND deleted_at IS NULL
              AND channel_id IN
              <foreach item="cid" collection="channelIds" open="(" separator="," close=")">#{cid}</foreach>
            <if test="cursorScore != null">
              AND ((view_count + like_count * 3 + comment_count * 5) &lt; #{cursorScore}
                   OR ((view_count + like_count * 3 + comment_count * 5) = #{cursorScore} AND id &lt; #{cursorId}))
            </if>
            ORDER BY (view_count + like_count * 3 + comment_count * 5) DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Post> selectFeedHot(@Param("channelIds") List<String> channelIds,
                             @Param("cursorScore") Long cursorScore,
                             @Param("cursorId") String cursorId,
                             @Param("limit") int limit);

    /**
     * 全站搜索帖子：仅返回公开频道下的已发布帖子（docs/03 FR-SRCH-09）。
     */
    @Select("""
            <script>
            SELECT p.* FROM posts p
            JOIN channels c ON c.id = p.channel_id
            WHERE p.status = 'PUBLISHED' AND p.deleted_at IS NULL
              AND c.status = 'ACTIVE' AND c.deleted_at IS NULL
              AND (c.type = 'PUBLIC' OR c.visibility = 'PUBLIC_VISIBLE')
              AND (p.title LIKE #{pattern} OR p.content LIKE #{pattern})
            <if test="cursorId != null">AND p.id &lt; #{cursorId}</if>
            ORDER BY p.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Post> searchVisiblePosts(@Param("pattern") String pattern,
                                  @Param("cursorId") String cursorId,
                                  @Param("limit") int limit);

    @Update("""
            UPDATE posts SET view_count = view_count + 1, updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    int addViewCount(@Param("id") String id);

    @Update("""
            UPDATE posts SET like_count = GREATEST(like_count + #{delta}, 0), updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    int addLikeCount(@Param("id") String id, @Param("delta") int delta);

    @Update("""
            UPDATE posts SET comment_count = GREATEST(comment_count + #{delta}, 0), updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    int addCommentCount(@Param("id") String id, @Param("delta") int delta);

    @Update("""
            UPDATE posts SET share_count = share_count + 1, updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    int addShareCount(@Param("id") String id);
}
