package com.isles.platform.announcement;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 公告数据访问。
 *
 * <p>公开查询只取「已发布 + 生效窗口内」的记录，排序按开始时间倒序（未设置开始时间的按创建时间）。
 */
@Mapper
public interface AnnouncementMapper extends BaseMapper<Announcement> {

    @Select("""
            <script>
            SELECT * FROM announcements
            <where>
                <if test="status != null">AND status = #{status}</if>
            </where>
            ORDER BY created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Announcement> selectPage(@Param("status") String status,
                                  @Param("limit") int limit,
                                  @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM announcements
            <where>
                <if test="status != null">AND status = #{status}</if>
            </where>
            </script>
            """)
    long countPage(@Param("status") String status);

    /**
     * 生效中的公告：已发布、已到开始时间、未过结束时间。
     */
    @Select("""
            SELECT * FROM announcements
            WHERE status = 'PUBLISHED'
              AND (start_at IS NULL OR start_at <= #{now})
              AND (end_at IS NULL OR end_at > #{now})
            ORDER BY start_at DESC, created_at DESC
            LIMIT #{limit}
            """)
    List<Announcement> selectActive(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
