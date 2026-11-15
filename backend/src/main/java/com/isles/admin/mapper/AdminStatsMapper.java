package com.isles.admin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 平台后台统计查询（docs/03 §4.16 FR-PADM-03）：日活粗略口径为当日有发帖/评论/点赞行为的去重用户。
 */
@Mapper
public interface AdminStatsMapper {

    @Select("""
            SELECT COUNT(DISTINCT uid) FROM (
                SELECT author_id AS uid FROM posts WHERE created_at >= #{since}
                UNION
                SELECT author_id AS uid FROM comments WHERE created_at >= #{since}
                UNION
                SELECT user_id AS uid FROM post_likes WHERE created_at >= #{since}
            ) active_users
            """)
    long countActiveUsersSince(@Param("since") LocalDateTime since);

    @Select("SELECT COUNT(*) FROM users WHERE created_at >= #{since} AND deleted_at IS NULL")
    long countUsersSince(@Param("since") LocalDateTime since);

    @Select("""
            SELECT COUNT(*) FROM posts
            WHERE created_at >= #{since} AND status = 'PUBLISHED' AND deleted_at IS NULL
            """)
    long countPostsSince(@Param("since") LocalDateTime since);
}
