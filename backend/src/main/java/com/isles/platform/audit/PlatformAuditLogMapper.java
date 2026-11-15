package com.isles.platform.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 平台审计数据访问：只提供插入（继承自 BaseMapper）与查询，不暴露更新/删除。
 */
@Mapper
public interface PlatformAuditLogMapper extends BaseMapper<PlatformAuditLog> {

    @Select("""
            <script>
            SELECT * FROM platform_audit_logs
            <where>
                <if test="actorId != null">AND actor_id = #{actorId}</if>
                <if test="action != null">AND action = #{action}</if>
                <if test="targetType != null">AND target_type = #{targetType}</if>
                <if test="targetId != null">AND target_id = #{targetId}</if>
                <if test="from != null">AND created_at &gt;= #{from}</if>
                <if test="to != null">AND created_at &lt; #{to}</if>
            </where>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<PlatformAuditLog> selectPage(@Param("actorId") String actorId,
                                      @Param("action") String action,
                                      @Param("targetType") String targetType,
                                      @Param("targetId") String targetId,
                                      @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to,
                                      @Param("limit") int limit,
                                      @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM platform_audit_logs
            <where>
                <if test="actorId != null">AND actor_id = #{actorId}</if>
                <if test="action != null">AND action = #{action}</if>
                <if test="targetType != null">AND target_type = #{targetType}</if>
                <if test="targetId != null">AND target_id = #{targetId}</if>
                <if test="from != null">AND created_at &gt;= #{from}</if>
                <if test="to != null">AND created_at &lt; #{to}</if>
            </where>
            </script>
            """)
    long countPage(@Param("actorId") String actorId,
                   @Param("action") String action,
                   @Param("targetType") String targetType,
                   @Param("targetId") String targetId,
                   @Param("from") LocalDateTime from,
                   @Param("to") LocalDateTime to);
}
