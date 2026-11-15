package com.isles.admin.mapper;

import com.isles.manage.entity.Report;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 平台举报数据访问（docs/11 §1.3 D3 修复）。
 *
 * <p>直接映射 {@link Report} 实体：此前用 {@code SELECT *} + Map + 表存在性探测，字段漂移会静默降级，
 * 掩盖真实问题。状态枚举与频道侧统一为 {@code PENDING/ACCEPTED/REJECTED}。
 */
@Mapper
public interface AdminReportMapper {

    @Select("""
            <script>
            SELECT * FROM reports
            <where>
                <if test="status != null">AND status = #{status}</if>
                <if test="targetType != null">AND target_type = #{targetType}</if>
                <if test="pattern != null">AND (description LIKE #{pattern} OR reason LIKE #{pattern})</if>
            </where>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Report> selectPage(@Param("status") String status,
                            @Param("targetType") String targetType,
                            @Param("pattern") String pattern,
                            @Param("limit") int limit,
                            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM reports
            <where>
                <if test="status != null">AND status = #{status}</if>
                <if test="targetType != null">AND target_type = #{targetType}</if>
                <if test="pattern != null">AND (description LIKE #{pattern} OR reason LIKE #{pattern})</if>
            </where>
            </script>
            """)
    long countPage(@Param("status") String status,
                   @Param("targetType") String targetType,
                   @Param("pattern") String pattern);

    @Select("SELECT * FROM reports WHERE id = #{id}")
    Report selectById(@Param("id") String id);

    /**
     * 处置：只允许从 PENDING 迁移，避免并发重复处置；返回 0 表示已被他人处理。
     */
    @Update("""
            UPDATE reports
            SET status = #{status},
                handler_id = #{handlerId},
                handle_action = #{handleAction},
                handle_note = #{note},
                handled_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int handle(@Param("id") String id,
               @Param("status") String status,
               @Param("handlerId") String handlerId,
               @Param("handleAction") String handleAction,
               @Param("note") String note);
}
