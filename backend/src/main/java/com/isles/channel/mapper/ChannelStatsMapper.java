package com.isles.channel.mapper;

import com.isles.channel.mapper.row.DateCountRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据中心聚合查询：从业务表按时间窗统计指标（无独立事件表时以明细表聚合，口径见 docs/03 第 8 章）。
 *
 * <p>所有查询均为只读参数化 SQL；统计任务可重复执行，结果按（频道 + 日期）upsert。
 */
@Mapper
public interface ChannelStatsMapper {

    @Select("""
            SELECT COUNT(*) FROM channel_members
            WHERE channel_id = #{channelId} AND status IN ('ACTIVE', 'MUTED')
            """)
    Long countActiveMembers(@Param("channelId") String channelId);

    @Select("""
            SELECT COUNT(*) FROM channel_members
            WHERE channel_id = #{channelId} AND joined_at >= #{from} AND joined_at < #{to}
              AND status IN ('ACTIVE', 'MUTED')
            """)
    Long countMembersJoinedBetween(@Param("channelId") String channelId,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    @Select("""
            SELECT COUNT(*) FROM channel_members
            WHERE channel_id = #{channelId} AND updated_at >= #{from} AND updated_at < #{to}
              AND status IN ('LEFT', 'KICKED')
            """)
    Long countMembersLeftBetween(@Param("channelId") String channelId,
                                 @Param("from") LocalDateTime from,
                                 @Param("to") LocalDateTime to);

    @Select("""
            SELECT COUNT(*) FROM posts
            WHERE channel_id = #{channelId} AND status = 'PUBLISHED'
              AND created_at >= #{from} AND created_at < #{to}
            """)
    Long countPostsBetween(@Param("channelId") String channelId,
                           @Param("from") LocalDateTime from,
                           @Param("to") LocalDateTime to);

    @Select("""
            SELECT COUNT(*) FROM comments c JOIN posts p ON p.id = c.post_id
            WHERE p.channel_id = #{channelId} AND c.status = 'PUBLISHED'
              AND c.created_at >= #{from} AND c.created_at < #{to}
            """)
    Long countCommentsBetween(@Param("channelId") String channelId,
                              @Param("from") LocalDateTime from,
                              @Param("to") LocalDateTime to);

    @Select("""
            SELECT COUNT(*) FROM post_likes pl JOIN posts p ON p.id = pl.post_id
            WHERE p.channel_id = #{channelId}
              AND pl.created_at >= #{from} AND pl.created_at < #{to}
            """)
    Long countPostLikesBetween(@Param("channelId") String channelId,
                               @Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to);

    @Select("""
            SELECT COUNT(*) FROM comment_likes cl
            JOIN comments c ON c.id = cl.comment_id
            JOIN posts p ON p.id = c.post_id
            WHERE p.channel_id = #{channelId}
              AND cl.created_at >= #{from} AND cl.created_at < #{to}
            """)
    Long countCommentLikesBetween(@Param("channelId") String channelId,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);

    /**
     * 分享次数以管理日志中的 post.share 动作为数据源（M1 分享无独立事件表）。
     */
    @Select("""
            SELECT COUNT(*) FROM channel_audit_logs
            WHERE channel_id = #{channelId} AND action = 'post.share'
              AND created_at >= #{from} AND created_at < #{to}
            """)
    Long countSharesBetween(@Param("channelId") String channelId,
                            @Param("from") LocalDateTime from,
                            @Param("to") LocalDateTime to);

    @Select("""
            SELECT DATE(joined_at) AS stat_date, COUNT(*) AS item_count
            FROM channel_members
            WHERE channel_id = #{channelId} AND joined_at >= #{from} AND joined_at < #{to}
              AND status IN ('ACTIVE', 'MUTED')
            GROUP BY DATE(joined_at)
            ORDER BY stat_date ASC
            """)
    List<DateCountRow> selectNewMemberTrend(@Param("channelId") String channelId,
                                            @Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to);
}
