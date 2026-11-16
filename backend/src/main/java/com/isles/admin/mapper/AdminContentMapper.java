package com.isles.admin.mapper;

import com.isles.admin.dto.AdminContentRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 平台内容治理数据访问（docs/11 §5.4）。
 *
 * <p>只读检索 + 状态变更；不做物理删除，下架统一写 {@code status=HIDDEN}，便于恢复与追溯。
 */
@Mapper
public interface AdminContentMapper {

    @Select("""
            <script>
            SELECT p.id, 'POST' AS targetType, p.channel_id AS channelId, p.author_id AS authorId,
                   p.title, LEFT(p.content, 200) AS contentPreview, p.status, p.created_at AS createdAt
            FROM posts p
            WHERE p.deleted_at IS NULL
            <if test="pattern != null">AND (p.title LIKE #{pattern} OR p.content LIKE #{pattern})</if>
            <if test="channelId != null">AND p.channel_id = #{channelId}</if>
            <if test="authorId != null">AND p.author_id = #{authorId}</if>
            <if test="status != null">AND p.status = #{status}</if>
            ORDER BY p.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AdminContentRow> selectPosts(@Param("pattern") String pattern,
                                      @Param("channelId") String channelId,
                                      @Param("authorId") String authorId,
                                      @Param("status") String status,
                                      @Param("limit") int limit,
                                      @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM posts p
            WHERE p.deleted_at IS NULL
            <if test="pattern != null">AND (p.title LIKE #{pattern} OR p.content LIKE #{pattern})</if>
            <if test="channelId != null">AND p.channel_id = #{channelId}</if>
            <if test="authorId != null">AND p.author_id = #{authorId}</if>
            <if test="status != null">AND p.status = #{status}</if>
            </script>
            """)
    long countPosts(@Param("pattern") String pattern,
                    @Param("channelId") String channelId,
                    @Param("authorId") String authorId,
                    @Param("status") String status);

    @Select("""
            <script>
            SELECT c.id, 'COMMENT' AS targetType, p.channel_id AS channelId, c.author_id AS authorId,
                   NULL AS title, LEFT(c.content, 200) AS contentPreview, c.status, c.created_at AS createdAt
            FROM comments c
            JOIN posts p ON p.id = c.post_id
            WHERE c.deleted_at IS NULL
            <if test="pattern != null">AND c.content LIKE #{pattern}</if>
            <if test="channelId != null">AND p.channel_id = #{channelId}</if>
            <if test="authorId != null">AND c.author_id = #{authorId}</if>
            <if test="status != null">AND c.status = #{status}</if>
            ORDER BY c.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AdminContentRow> selectComments(@Param("pattern") String pattern,
                                         @Param("channelId") String channelId,
                                         @Param("authorId") String authorId,
                                         @Param("status") String status,
                                         @Param("limit") int limit,
                                         @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM comments c
            JOIN posts p ON p.id = c.post_id
            WHERE c.deleted_at IS NULL
            <if test="pattern != null">AND c.content LIKE #{pattern}</if>
            <if test="channelId != null">AND p.channel_id = #{channelId}</if>
            <if test="authorId != null">AND c.author_id = #{authorId}</if>
            <if test="status != null">AND c.status = #{status}</if>
            </script>
            """)
    long countComments(@Param("pattern") String pattern,
                       @Param("channelId") String channelId,
                       @Param("authorId") String authorId,
                       @Param("status") String status);

    @Update("""
            UPDATE posts SET status = #{status}, updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int updatePostStatus(@Param("id") String id, @Param("status") String status);

    /**
     * 评论状态变更。
     *
     * <p>注意：{@code comments} 表**没有 updated_at 列**（只有 created_at / deleted_at），
     * 这里只能改 status；早期版本多写了一列 updated_at，导致评论类举报处置直接
     * BadSqlGrammarException（500）。
     */
    @Update("""
            UPDATE comments SET status = #{status}
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int updateCommentStatus(@Param("id") String id, @Param("status") String status);

    @Select("""
            SELECT p.id, 'POST' AS targetType, p.channel_id AS channelId, p.author_id AS authorId,
                   p.title, LEFT(p.content, 200) AS contentPreview, p.status, p.created_at AS createdAt
            FROM posts p WHERE p.id = #{id} AND p.deleted_at IS NULL
            """)
    AdminContentRow selectPost(@Param("id") String id);

    @Select("""
            SELECT c.id, 'COMMENT' AS targetType, p.channel_id AS channelId, c.author_id AS authorId,
                   NULL AS title, LEFT(c.content, 200) AS contentPreview, c.status, c.created_at AS createdAt
            FROM comments c JOIN posts p ON p.id = c.post_id
            WHERE c.id = #{id} AND c.deleted_at IS NULL
            """)
    AdminContentRow selectComment(@Param("id") String id);
}
