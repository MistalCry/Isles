package com.isles.admin.mapper;

import com.isles.channel.entity.Channel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 平台后台频道管理查询（禁用/解禁、关键词搜索、分页）。
 */
@Mapper
public interface AdminChannelMapper {

    @Select("""
            <script>
            SELECT * FROM channels
            WHERE deleted_at IS NULL
            <if test="pattern != null">AND (name LIKE #{pattern} OR CAST(channel_no AS CHAR) LIKE #{pattern})</if>
            <if test="status != null">AND status = #{status}</if>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Channel> selectPage(@Param("pattern") String pattern,
                             @Param("status") String status,
                             @Param("limit") int limit,
                             @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM channels
            WHERE deleted_at IS NULL
            <if test="pattern != null">AND (name LIKE #{pattern} OR CAST(channel_no AS CHAR) LIKE #{pattern})</if>
            <if test="status != null">AND status = #{status}</if>
            </script>
            """)
    long countPage(@Param("pattern") String pattern, @Param("status") String status);

    @Update("""
            UPDATE channels SET status = #{status}, updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int updateStatus(@Param("id") String id, @Param("status") String status);
}
