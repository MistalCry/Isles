package com.isles.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.channel.audit.ChannelAuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 频道管理日志数据访问：按频道 + 操作者/动作/目标/时间筛选，ULID 游标分页（时间倒序）。
 */
@Mapper
public interface ChannelAuditLogMapper extends BaseMapper<ChannelAuditLog> {

    @Select("""
            <script>
            SELECT * FROM channel_audit_logs
            WHERE channel_id = #{channelId}
            <if test="actorId != null">AND actor_id = #{actorId}</if>
            <if test="action != null">AND action = #{action}</if>
            <if test="targetType != null">AND target_type = #{targetType}</if>
            <if test="from != null">AND created_at &gt;= #{from}</if>
            <if test="to != null">AND created_at &lt; #{to}</if>
            <if test="cursorId != null">AND id &lt; #{cursorId}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<ChannelAuditLog> selectPage(@Param("channelId") String channelId,
                                     @Param("actorId") String actorId,
                                     @Param("action") String action,
                                     @Param("targetType") String targetType,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to,
                                     @Param("cursorId") String cursorId,
                                     @Param("limit") int limit);

    /**
     * 管理端偏移分页（page/size）。
     */
    @Select("""
            <script>
            SELECT * FROM channel_audit_logs
            WHERE channel_id = #{channelId}
            <if test="actorId != null">AND actor_id = #{actorId}</if>
            <if test="action != null">AND action = #{action}</if>
            <if test="targetType != null">AND target_type = #{targetType}</if>
            <if test="from != null">AND created_at &gt;= #{from}</if>
            <if test="to != null">AND created_at &lt; #{to}</if>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ChannelAuditLog> selectPageOffset(@Param("channelId") String channelId,
                                           @Param("actorId") String actorId,
                                           @Param("action") String action,
                                           @Param("targetType") String targetType,
                                           @Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to,
                                           @Param("offset") int offset,
                                           @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM channel_audit_logs
            WHERE channel_id = #{channelId}
            <if test="actorId != null">AND actor_id = #{actorId}</if>
            <if test="action != null">AND action = #{action}</if>
            <if test="targetType != null">AND target_type = #{targetType}</if>
            <if test="from != null">AND created_at &gt;= #{from}</if>
            <if test="to != null">AND created_at &lt; #{to}</if>
            </script>
            """)
    long countPage(@Param("channelId") String channelId,
                   @Param("actorId") String actorId,
                   @Param("action") String action,
                   @Param("targetType") String targetType,
                   @Param("from") LocalDateTime from,
                   @Param("to") LocalDateTime to);
}
