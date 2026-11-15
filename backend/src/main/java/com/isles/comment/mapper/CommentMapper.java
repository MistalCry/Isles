package com.isles.comment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.comment.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 评论数据访问：顶级评论按最新/点赞排序；回复按父评论批量查询（避免 N+1）。
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    /**
     * 顶级评论 - 最新。
     */
    @Select("""
            <script>
            SELECT * FROM comments
            WHERE post_id = #{postId} AND parent_id IS NULL AND status = 'PUBLISHED'
            <if test="cursorId != null">AND id &lt; #{cursorId}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Comment> selectTopLatest(@Param("postId") String postId,
                                  @Param("cursorId") String cursorId,
                                  @Param("limit") int limit);

    /**
     * 顶级评论 - 点赞数排序（点赞游标）。
     */
    @Select("""
            <script>
            SELECT * FROM comments
            WHERE post_id = #{postId} AND parent_id IS NULL AND status = 'PUBLISHED'
            <if test="cursorScore != null">
              AND (like_count &lt; #{cursorScore} OR (like_count = #{cursorScore} AND id &lt; #{cursorId}))
            </if>
            ORDER BY like_count DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Comment> selectTopLiked(@Param("postId") String postId,
                                 @Param("cursorScore") Long cursorScore,
                                 @Param("cursorId") String cursorId,
                                 @Param("limit") int limit);

    /**
     * 批量取每个帖子的「最高赞评论」各 1 条（帖子卡片预览用，docs/05 §2.3）。
     *
     * <p>用窗口函数一次取全，避免按帖循环查询造成 N+1；同赞数时取更早的评论（id 升序）。
     */
    @Select("""
            <script>
            SELECT * FROM (
                SELECT c.*, ROW_NUMBER() OVER (
                           PARTITION BY c.post_id ORDER BY c.like_count DESC, c.id ASC
                       ) AS rn
                FROM comments c
                WHERE c.status = 'PUBLISHED' AND c.deleted_at IS NULL
                  AND c.post_id IN
                  <foreach item="pid" collection="postIds" open="(" separator="," close=")">#{pid}</foreach>
            ) t
            WHERE t.rn = 1
            </script>
            """)
    List<Comment> selectTopLikedByPostIds(@Param("postIds") List<String> postIds);

    @Update("""
            UPDATE comments SET like_count = GREATEST(like_count + #{delta}, 0)
            WHERE id = #{id}
            """)
    int addLikeCount(@Param("id") String id, @Param("delta") int delta);
}
