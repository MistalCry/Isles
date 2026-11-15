package com.isles.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.analytics.entity.DailyChannelStats;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 频道日聚合统计数据访问：唯一键 (channel_id, stat_date) + upsert，重复聚合不会重复计数。
 */
@Mapper
public interface DailyChannelStatsMapper extends BaseMapper<DailyChannelStats> {

    @Insert("""
            INSERT INTO daily_channel_stats
                (id, channel_id, stat_date, member_count, new_members, lost_members, visit_uv,
                 post_views, active_users, active_rate, posts, comments, likes, shares,
                 interactions, interaction_rate, created_at, updated_at)
            VALUES
                (#{id}, #{channelId}, #{statDate}, #{memberCount}, #{newMembers}, #{lostMembers}, #{visitUv},
                 #{postViews}, #{activeUsers}, #{activeRate}, #{posts}, #{comments}, #{likes}, #{shares},
                 #{interactions}, #{interactionRate}, #{createdAt}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                member_count = VALUES(member_count),
                new_members = VALUES(new_members),
                lost_members = VALUES(lost_members),
                visit_uv = VALUES(visit_uv),
                post_views = VALUES(post_views),
                active_users = VALUES(active_users),
                active_rate = VALUES(active_rate),
                posts = VALUES(posts),
                comments = VALUES(comments),
                likes = VALUES(likes),
                shares = VALUES(shares),
                interactions = VALUES(interactions),
                interaction_rate = VALUES(interaction_rate),
                updated_at = CURRENT_TIMESTAMP(3)
            """)
    int upsert(DailyChannelStats stats);

    @Select("""
            SELECT * FROM daily_channel_stats
            WHERE channel_id = #{channelId} AND stat_date = #{statDate}
            """)
    DailyChannelStats selectByChannelAndDate(@Param("channelId") String channelId,
                                             @Param("statDate") LocalDate statDate);

    @Select("""
            SELECT * FROM daily_channel_stats
            WHERE channel_id = #{channelId} AND stat_date BETWEEN #{from} AND #{to}
            ORDER BY stat_date ASC
            """)
    List<DailyChannelStats> selectRange(@Param("channelId") String channelId,
                                        @Param("from") LocalDate from,
                                        @Param("to") LocalDate to);
}
