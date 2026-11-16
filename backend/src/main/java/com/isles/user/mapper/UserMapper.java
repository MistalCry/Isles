package com.isles.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户表数据访问（常规 CRUD 交给 MyBatis-Plus BaseMapper，复杂查询写参数化 SQL）。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 按 id 游标分页取活跃用户 id（站内信群发用，避免 OFFSET 深分页）。
     *
     * @param afterId 上一批最后一个 id；null 表示从头开始
     */
    @Select("""
            <script>
            SELECT id FROM users
            WHERE status = 'ACTIVE' AND deleted_at IS NULL
            <if test="afterId != null">AND id &gt; #{afterId}</if>
            ORDER BY id ASC
            LIMIT #{limit}
            </script>
            """)
    List<String> selectActiveIdsAfter(@Param("afterId") String afterId, @Param("limit") int limit);
}
