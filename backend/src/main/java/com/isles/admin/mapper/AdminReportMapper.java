package com.isles.admin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 举报数据访问（兼容协作依赖）。
 *
 * <p>{@code reports} 表由 WP1（backend-dev）的 V2 迁移创建（docs/06 §4 预留表）。
 * 本模块先检测表是否存在：未就绪时接口返回 available=false，处置动作返回明确业务错误，
 * 不创建该表以避免与 V2 迁移冲突。查询使用 {@code SELECT *} + Map 映射，兼容 V2 的字段命名差异。
 */
@Mapper
public interface AdminReportMapper {

    @Select("""
            SELECT COUNT(*) FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name = 'reports'
            """)
    long countReportsTable();

    @Select("""
            <script>
            SELECT * FROM reports
            <if test="status != null">WHERE status = #{status}</if>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Map<String, Object>> selectPage(@Param("status") String status,
                                         @Param("limit") int limit,
                                         @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM reports
            <if test="status != null">WHERE status = #{status}</if>
            </script>
            """)
    long countPage(@Param("status") String status);

    @Update("UPDATE reports SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") String id, @Param("status") String status);
}
