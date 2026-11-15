package com.isles.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.post.entity.Post;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
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

    /**
     * 内容管理列表：关键词/发帖人/板块/状态/时间筛选，ULID 游标分页（含已删除/隐藏）。
     */
    @Select("""
            <script>
            SELECT * FROM posts
            WHERE channel_id = #{channelId}
            <if test="status != null">AND status = #{status}</if>
            <if test="keywordPattern != null">AND (title LIKE #{keywordPattern} OR content LIKE #{keywordPattern})</if>
            <if test="authorId != null">AND author_id = #{authorId}</if>
            <if test="boardId != null">AND board_id = #{boardId}</if>
            <if test="from != null">AND created_at &gt;= #{from}</if>
            <if test="to != null">AND created_at &lt; #{to}</if>
            <if test="cursorId != null">AND id &lt; #{cursorId}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Post> selectChannelManage(@Param("channelId") String channelId,
                                   @Param("status") String status,
                                   @Param("keywordPattern") String keywordPattern,
                                   @Param("authorId") String authorId,
                                   @Param("boardId") String boardId,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to,
                                   @Param("cursorId") String cursorId,
                                   @Param("limit") int limit);

    /**
     * 内容管理列表 - 偏移分页（管理端 page/size）。
     */
    @Select("""
            <script>
            SELECT * FROM posts
            WHERE channel_id = #{channelId}
            <if test="status != null">AND status = #{status}</if>
            <if test="keywordPattern != null">AND (title LIKE #{keywordPattern} OR content LIKE #{keywordPattern})</if>
            <if test="authorId != null">AND author_id = #{authorId}</if>
            <if test="boardId != null">AND board_id = #{boardId}</if>
            <if test="from != null">AND created_at &gt;= #{from}</if>
            <if test="to != null">AND created_at &lt; #{to}</if>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Post> selectChannelManageOffset(@Param("channelId") String channelId,
                                         @Param("status") String status,
                                         @Param("keywordPattern") String keywordPattern,
                                         @Param("authorId") String authorId,
                                         @Param("boardId") String boardId,
                                         @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to,
                                         @Param("offset") int offset,
                                         @Param("limit") int limit);

    /**
     * 内容管理列表总数（与 {@link #selectChannelManageOffset} 条件一致）。
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM posts
            WHERE channel_id = #{channelId}
            <if test="status != null">AND status = #{status}</if>
            <if test="keywordPattern != null">AND (title LIKE #{keywordPattern} OR content LIKE #{keywordPattern})</if>
            <if test="authorId != null">AND author_id = #{authorId}</if>
            <if test="boardId != null">AND board_id = #{boardId}</if>
            <if test="from != null">AND created_at &gt;= #{from}</if>
            <if test="to != null">AND created_at &lt; #{to}</if>
            </script>
            """)
    long countChannelManage(@Param("channelId") String channelId,
                            @Param("status") String status,
                            @Param("keywordPattern") String keywordPattern,
                            @Param("authorId") String authorId,
                            @Param("boardId") String boardId,
                            @Param("from") LocalDateTime from,
                            @Param("to") LocalDateTime to);

    /**
     * 全站热门讨论：公开频道下已发布帖子，热度 = (浏览 + 点赞×3 + 评论×5) / (小时数 + 2)^1.5。
     * 游标使用"热度分 × 1e6 取整" + ULID，避免浮点数游标不稳定。
     */
    @Select("""
            <script>
            SELECT p.* FROM posts p
            WHERE p.status = 'PUBLISHED' AND p.deleted_at IS NULL
              AND p.created_at &gt;= #{since}
              AND EXISTS (
                  SELECT 1 FROM channels c
                  WHERE c.id = p.channel_id AND c.status = 'ACTIVE' AND c.deleted_at IS NULL
                    AND (c.type = 'PUBLIC' OR c.visibility = 'PUBLIC_VISIBLE')
              )
            <if test="cursorScore != null">
              AND (FLOOR(1000000 * (p.view_count + p.like_count * 3 + p.comment_count * 5)
                         / POW(TIMESTAMPDIFF(HOUR, p.created_at, UTC_TIMESTAMP()) + 2, 1.5)) &lt; #{cursorScore}
                   OR (FLOOR(1000000 * (p.view_count + p.like_count * 3 + p.comment_count * 5)
                         / POW(TIMESTAMPDIFF(HOUR, p.created_at, UTC_TIMESTAMP()) + 2, 1.5)) = #{cursorScore}
                       AND p.id &lt; #{cursorId}))
            </if>
            ORDER BY FLOOR(1000000 * (p.view_count + p.like_count * 3 + p.comment_count * 5)
                           / POW(TIMESTAMPDIFF(HOUR, p.created_at, UTC_TIMESTAMP()) + 2, 1.5)) DESC,
                     p.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Post> selectHotGlobal(@Param("cursorScore") Long cursorScore,
                               @Param("cursorId") String cursorId,
                               @Param("since") LocalDateTime since,
                               @Param("limit") int limit);

    /**
     * 搜索帖子 - 相关度排序（标题命中优先），偏移分页。
     */
    @Select("""
            SELECT p.* FROM posts p
            JOIN channels c ON c.id = p.channel_id
            WHERE p.status = 'PUBLISHED' AND p.deleted_at IS NULL
              AND c.status = 'ACTIVE' AND c.deleted_at IS NULL
              AND (c.type = 'PUBLIC' OR c.visibility = 'PUBLIC_VISIBLE')
              AND (p.title LIKE #{pattern} OR p.content LIKE #{pattern})
            ORDER BY (p.title LIKE #{pattern}) DESC, p.like_count DESC, p.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<Post> searchVisiblePostsByRelevance(@Param("pattern") String pattern,
                                             @Param("offset") int offset,
                                             @Param("limit") int limit);

    /**
     * 频道内搜索 - 相关度排序。
     */
    @Select("""
            <script>
            SELECT * FROM posts
            WHERE channel_id = #{channelId} AND status = 'PUBLISHED' AND deleted_at IS NULL
              AND (title LIKE #{pattern} OR content LIKE #{pattern})
            <if test="boardId != null">AND board_id = #{boardId}</if>
            ORDER BY (title LIKE #{pattern}) DESC, like_count DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Post> searchInChannelByRelevance(@Param("channelId") String channelId,
                                          @Param("boardId") String boardId,
                                          @Param("pattern") String pattern,
                                          @Param("offset") int offset,
                                          @Param("limit") int limit);

    /**
     * 频道内搜索 - 最新排序。
     */
    @Select("""
            <script>
            SELECT * FROM posts
            WHERE channel_id = #{channelId} AND status = 'PUBLISHED' AND deleted_at IS NULL
              AND (title LIKE #{pattern} OR content LIKE #{pattern})
            <if test="boardId != null">AND board_id = #{boardId}</if>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Post> searchInChannelLatest(@Param("channelId") String channelId,
                                     @Param("boardId") String boardId,
                                     @Param("pattern") String pattern,
                                     @Param("offset") int offset,
                                     @Param("limit") int limit);

    /**
     * 推荐规则：候选频道在时间窗内的新增帖子数。
     */
    @Select("""
            <script>
            SELECT channel_id, COUNT(*) AS post_count FROM posts
            WHERE channel_id IN
                <foreach item="cid" collection="channelIds" open="(" separator="," close=")">#{cid}</foreach>
              AND status = 'PUBLISHED' AND deleted_at IS NULL
              AND created_at &gt;= #{since}
            GROUP BY channel_id
            </script>
            """)
    List<com.isles.post.mapper.row.ChannelPostCountRow> countPostsByChannelIdsSince(
            @Param("channelIds") List<String> channelIds,
            @Param("since") LocalDateTime since);

    /**
     * 内容分析 - Top 帖子（按浏览量）。
     */
    @Select("""
            SELECT * FROM posts
            WHERE channel_id = #{channelId} AND status = 'PUBLISHED' AND deleted_at IS NULL
            ORDER BY view_count DESC, like_count DESC, id DESC
            LIMIT #{limit}
            """)
    List<Post> selectTopPosts(@Param("channelId") String channelId, @Param("limit") int limit);

    /**
     * 内容分析 - 板块分布。
     */
    @Select("""
            SELECT p.board_id AS board_id, b.name AS board_name, COUNT(*) AS post_count
            FROM posts p JOIN boards b ON b.id = p.board_id
            WHERE p.channel_id = #{channelId} AND p.status = 'PUBLISHED' AND p.deleted_at IS NULL
            GROUP BY p.board_id, b.name
            ORDER BY post_count DESC
            """)
    List<com.isles.post.mapper.row.BoardPostCountRow> countPostsByBoard(@Param("channelId") String channelId);

    @Update("""
            UPDATE posts SET is_pinned = #{pinned}, pinned_at = #{pinnedAt}, updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    int updatePinned(@Param("id") String id, @Param("pinned") boolean pinned,
                     @Param("pinnedAt") LocalDateTime pinnedAt);

    @Update("""
            UPDATE posts SET is_essence = #{essence}, updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    int updateEssence(@Param("id") String id, @Param("essence") boolean essence);

    @Update("""
            UPDATE posts SET status = 'DELETED', deleted_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    int softDelete(@Param("id") String id);

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
