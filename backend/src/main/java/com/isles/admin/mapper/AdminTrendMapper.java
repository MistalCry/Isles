package com.isles.admin.mapper;

import com.isles.admin.dto.AdminTrendRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 平台趋势聚合（docs/11 §5.5）：按 UTC 自然日 GROUP BY，时间窗口由服务层限制。
 *
 * <p>数据量为小型社区规模（万级日增），直接聚合即可；后续增长可物化为日统计表。
 */
@Mapper
public interface AdminTrendMapper {

    @Select("""
            SELECT DATE(created_at) AS day, COUNT(*) AS total
            FROM users
            WHERE deleted_at IS NULL AND created_at >= #{from}
            GROUP BY DATE(created_at)
            """)
    List<AdminTrendRow> countUsersByDay(@Param("from") LocalDateTime from);

    @Select("""
            SELECT DATE(created_at) AS day, COUNT(*) AS total
            FROM posts
            WHERE deleted_at IS NULL AND created_at >= #{from}
            GROUP BY DATE(created_at)
            """)
    List<AdminTrendRow> countPostsByDay(@Param("from") LocalDateTime from);

    @Select("""
            SELECT DATE(created_at) AS day, COUNT(*) AS total
            FROM comments
            WHERE deleted_at IS NULL AND created_at >= #{from}
            GROUP BY DATE(created_at)
            """)
    List<AdminTrendRow> countCommentsByDay(@Param("from") LocalDateTime from);

    /**
     * 活跃用户：当日发帖或评论的去重用户数。
     */
    @Select("""
            SELECT day, COUNT(DISTINCT user_id) AS total FROM (
                SELECT DATE(created_at) AS day, author_id AS user_id
                FROM posts WHERE deleted_at IS NULL AND created_at >= #{from}
                UNION ALL
                SELECT DATE(created_at) AS day, author_id AS user_id
                FROM comments WHERE deleted_at IS NULL AND created_at >= #{from}
            ) t
            GROUP BY day
            """)
    List<AdminTrendRow> countActiveUsersByDay(@Param("from") LocalDateTime from);
}
