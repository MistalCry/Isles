package com.isles.apps.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.apps.entity.ChannelActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 频道活动数据访问（V10）。
 */
@Mapper
public interface ChannelActivityMapper extends BaseMapper<ChannelActivity> {

    @Select("""
            <script>
            SELECT * FROM channel_activities
            WHERE channel_id = #{channelId}
            <if test="status != null">AND status = #{status}</if>
            ORDER BY start_at ASC, id ASC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ChannelActivity> selectByChannel(@Param("channelId") String channelId,
                                          @Param("status") String status,
                                          @Param("limit") int limit,
                                          @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM channel_activities
            WHERE channel_id = #{channelId}
            <if test="status != null">AND status = #{status}</if>
            </script>
            """)
    long countByChannel(@Param("channelId") String channelId, @Param("status") String status);

    /**
     * 待提醒活动：已发布、未提醒、开始时间落在 [from, to)。
     */
    @Select("""
            SELECT * FROM channel_activities
            WHERE status = 'PUBLISHED' AND reminder_sent_at IS NULL
              AND start_at >= #{from} AND start_at < #{to}
            ORDER BY start_at ASC
            LIMIT #{limit}
            """)
    List<ChannelActivity> selectDueReminders(@Param("from") LocalDateTime from,
                                             @Param("to") LocalDateTime to,
                                             @Param("limit") int limit);

    /**
     * 抢占提醒标记（幂等）：只有把 reminder_sent_at 从 NULL 改成时间的那一次返回 1。
     */
    @Update("""
            UPDATE channel_activities SET reminder_sent_at = #{sentAt}
            WHERE id = #{id} AND reminder_sent_at IS NULL
            """)
    int claimReminder(@Param("id") String id, @Param("sentAt") LocalDateTime sentAt);
}
