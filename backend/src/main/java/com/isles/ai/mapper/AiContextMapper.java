package com.isles.ai.mapper;

import com.isles.ai.dto.AiContextPost;
import com.isles.ai.dto.AiContributorRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 检索上下文数据访问（docs/03 §7「AI 上下文检索」）。
 *
 * <p>只检索公开可见频道下的已发布帖子：{@code type = 'PUBLIC' OR visibility = 'PUBLIC_VISIBLE'}，
 * 防止 AI 回答泄露私密内容（docs/07 §1 AI 提示注入威胁）。频道助手场景额外限定 channel_id。
 * 关键词用 LIKE 参数化模糊匹配（先做关键词长度与通配符清洗），排序按站内热度近似。
 */
@Mapper
public interface AiContextMapper {

    String CONTEXT_COLUMNS = """
            p.id AS id, p.channel_id AS channelId, c.name AS channelName, p.author_id AS authorId,
            p.title AS title, p.content AS content,
            p.view_count AS viewCount, p.like_count AS likeCount, p.comment_count AS commentCount,
            p.created_at AS createdAt
            """;

    /**
     * 关键词检索 TopN 公开帖子。
     */
    @Select("""
            <script>
            SELECT
            """ + CONTEXT_COLUMNS + """
            FROM posts p
            JOIN channels c ON c.id = p.channel_id
            WHERE p.status = 'PUBLISHED' AND p.deleted_at IS NULL
              AND c.status = 'ACTIVE' AND c.deleted_at IS NULL
              AND (c.type = 'PUBLIC' OR c.visibility = 'PUBLIC_VISIBLE')
            <if test="channelId != null">AND p.channel_id = #{channelId}</if>
              AND
              <foreach item="kw" collection="patterns" open="(" separator=" OR " close=")">
                (p.title LIKE #{kw} OR p.content LIKE #{kw})
              </foreach>
            ORDER BY (p.view_count + p.like_count * 3 + p.comment_count * 5) DESC, p.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<AiContextPost> searchContextPosts(@Param("patterns") List<String> patterns,
                                           @Param("channelId") String channelId,
                                           @Param("limit") int limit);

    /**
     * 最近帖子（无关键词命中时的兜底上下文 / 自动任务素材 / 热门内容检索）。
     */
    @Select("""
            <script>
            SELECT
            """ + CONTEXT_COLUMNS + """
            FROM posts p
            JOIN channels c ON c.id = p.channel_id
            WHERE p.status = 'PUBLISHED' AND p.deleted_at IS NULL
              AND c.status = 'ACTIVE' AND c.deleted_at IS NULL
              AND (c.type = 'PUBLIC' OR c.visibility = 'PUBLIC_VISIBLE')
            <if test="channelId != null">AND p.channel_id = #{channelId}</if>
            <if test="since != null">AND p.created_at &gt;= #{since}</if>
            ORDER BY (p.view_count + p.like_count * 3 + p.comment_count * 5) DESC, p.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<AiContextPost> selectRecentPosts(@Param("channelId") String channelId,
                                          @Param("since") LocalDateTime since,
                                          @Param("limit") int limit);

    /**
     * 月度贡献榜：按发帖数与获赞数聚合（docs/03 FR-ASSIST-03）。
     */
    @Select("""
            SELECT p.author_id AS userId, u.nickname AS nickname,
                   COUNT(*) AS postCount, COALESCE(SUM(p.like_count), 0) AS likeSum
            FROM posts p
            JOIN users u ON u.id = p.author_id
            WHERE p.channel_id = #{channelId} AND p.status = 'PUBLISHED' AND p.deleted_at IS NULL
              AND p.created_at >= #{since} AND p.created_at < #{until}
            GROUP BY p.author_id, u.nickname
            ORDER BY COUNT(*) DESC, COALESCE(SUM(p.like_count), 0) DESC
            LIMIT #{limit}
            """)
    List<AiContributorRow> selectMonthlyContributors(@Param("channelId") String channelId,
                                                     @Param("since") LocalDateTime since,
                                                     @Param("until") LocalDateTime until,
                                                     @Param("limit") int limit);
}
