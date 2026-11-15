package com.isles.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.analytics.entity.ChannelViewLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 频道访问日志数据访问：为数据中心 UV/PV/活跃用户提供聚合数据源。
 */
@Mapper
public interface ChannelViewLogMapper extends BaseMapper<ChannelViewLog> {

    @Select("""
            SELECT COUNT(*) FROM channel_view_logs
            WHERE channel_id = #{channelId} AND created_at >= #{from} AND created_at < #{to}
            """)
    Long countViews(@Param("channelId") String channelId,
                    @Param("from") LocalDateTime from,
                    @Param("to") LocalDateTime to);

    @Select("""
            SELECT COUNT(DISTINCT user_id) FROM channel_view_logs
            WHERE channel_id = #{channelId} AND created_at >= #{from} AND created_at < #{to}
            """)
    Long countDistinctViewers(@Param("channelId") String channelId,
                              @Param("from") LocalDateTime from,
                              @Param("to") LocalDateTime to);

    /**
     * 当日有行为的成员（发帖/评论/点赞/浏览/分享），仅统计频道有效成员。
     */
    @Select("""
            SELECT DISTINCT t.uid
            FROM (
                SELECT author_id AS uid FROM posts
                WHERE channel_id = #{channelId} AND status = 'PUBLISHED'
                  AND created_at >= #{from} AND created_at < #{to}
                UNION ALL
                SELECT c.author_id FROM comments c JOIN posts p ON p.id = c.post_id
                WHERE p.channel_id = #{channelId} AND c.status = 'PUBLISHED'
                  AND c.created_at >= #{from} AND c.created_at < #{to}
                UNION ALL
                SELECT pl.user_id FROM post_likes pl JOIN posts p ON p.id = pl.post_id
                WHERE p.channel_id = #{channelId}
                  AND pl.created_at >= #{from} AND pl.created_at < #{to}
                UNION ALL
                SELECT cl.user_id FROM comment_likes cl
                JOIN comments c ON c.id = cl.comment_id
                JOIN posts p ON p.id = c.post_id
                WHERE p.channel_id = #{channelId}
                  AND cl.created_at >= #{from} AND cl.created_at < #{to}
                UNION ALL
                SELECT vl.user_id FROM channel_view_logs vl
                WHERE vl.channel_id = #{channelId}
                  AND vl.created_at >= #{from} AND vl.created_at < #{to}
                UNION ALL
                SELECT al.actor_id FROM channel_audit_logs al
                WHERE al.channel_id = #{channelId} AND al.action = 'post.share'
                  AND al.created_at >= #{from} AND al.created_at < #{to}
            ) t
            JOIN channel_members m ON m.user_id = t.uid
                AND m.channel_id = #{channelId}
                AND m.status IN ('ACTIVE', 'MUTED')
            WHERE t.uid IS NOT NULL
            """)
    List<String> selectActiveUserIds(@Param("channelId") String channelId,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);
}
