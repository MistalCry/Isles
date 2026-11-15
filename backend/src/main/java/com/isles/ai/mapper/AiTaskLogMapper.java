package com.isles.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.ai.entity.AiTaskLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 频道助手任务日志数据访问：幂等查询、频率限额统计、管理端分页。
 */
@Mapper
public interface AiTaskLogMapper extends BaseMapper<AiTaskLog> {

    @Select("""
            SELECT * FROM ai_task_logs
            WHERE channel_id = #{channelId} AND task_type = #{taskType} AND frequency_key = #{frequencyKey}
            LIMIT 1
            """)
    AiTaskLog selectByKey(@Param("channelId") String channelId,
                          @Param("taskType") String taskType,
                          @Param("frequencyKey") String frequencyKey);

    /**
     * 指定时间段内成功执行的次数（自动发帖/评论/点赞的每日频率上限）。
     */
    @Select("""
            SELECT COUNT(*) FROM ai_task_logs
            WHERE channel_id = #{channelId} AND task_type = #{taskType} AND status = 'SUCCESS'
              AND created_at >= #{since} AND created_at < #{until}
            """)
    long countSuccessBetween(@Param("channelId") String channelId,
                             @Param("taskType") String taskType,
                             @Param("since") LocalDateTime since,
                             @Param("until") LocalDateTime until);

    @Select("""
            <script>
            SELECT * FROM ai_task_logs
            WHERE channel_id = #{channelId}
            <if test="taskType != null">AND task_type = #{taskType}</if>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AiTaskLog> selectPage(@Param("channelId") String channelId,
                               @Param("taskType") String taskType,
                               @Param("limit") int limit,
                               @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM ai_task_logs
            WHERE channel_id = #{channelId}
            <if test="taskType != null">AND task_type = #{taskType}</if>
            </script>
            """)
    long countPage(@Param("channelId") String channelId, @Param("taskType") String taskType);

    @Select("SELECT COUNT(*) FROM ai_task_logs WHERE status = #{status}")
    long countByStatus(@Param("status") String status);
}
