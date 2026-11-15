package com.isles.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.manage.entity.Report;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 举报数据访问：我的举报 / 频道待处理列表，ULID 游标分页。
 */
@Mapper
public interface ReportMapper extends BaseMapper<Report> {

    @Select("""
            <script>
            SELECT * FROM reports
            WHERE 1 = 1
            <if test="reporterId != null">AND reporter_id = #{reporterId}</if>
            <if test="channelId != null">AND channel_id = #{channelId}</if>
            <if test="status != null">AND status = #{status}</if>
            <if test="targetType != null">AND target_type = #{targetType}</if>
            <if test="cursorId != null">AND id &lt; #{cursorId}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Report> selectPage(@Param("reporterId") String reporterId,
                            @Param("channelId") String channelId,
                            @Param("status") String status,
                            @Param("targetType") String targetType,
                            @Param("cursorId") String cursorId,
                            @Param("limit") int limit);

    /**
     * 举报管理 - 偏移分页（page/size）。
     */
    @Select("""
            <script>
            SELECT * FROM reports
            WHERE 1 = 1
            <if test="reporterId != null">AND reporter_id = #{reporterId}</if>
            <if test="channelId != null">AND channel_id = #{channelId}</if>
            <if test="status != null">AND status = #{status}</if>
            <if test="targetType != null">AND target_type = #{targetType}</if>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Report> selectPageOffset(@Param("reporterId") String reporterId,
                                  @Param("channelId") String channelId,
                                  @Param("status") String status,
                                  @Param("targetType") String targetType,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM reports
            WHERE 1 = 1
            <if test="reporterId != null">AND reporter_id = #{reporterId}</if>
            <if test="channelId != null">AND channel_id = #{channelId}</if>
            <if test="status != null">AND status = #{status}</if>
            <if test="targetType != null">AND target_type = #{targetType}</if>
            </script>
            """)
    long countPage(@Param("reporterId") String reporterId,
                   @Param("channelId") String channelId,
                   @Param("status") String status,
                   @Param("targetType") String targetType);
}
