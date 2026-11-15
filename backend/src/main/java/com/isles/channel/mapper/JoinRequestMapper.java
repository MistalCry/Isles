package com.isles.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.channel.entity.JoinRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 加入申请数据访问。
 */
@Mapper
public interface JoinRequestMapper extends BaseMapper<JoinRequest> {

    /**
     * 加入审批列表（按状态筛选，ULID 游标分页）。
     */
    @Select("""
            <script>
            SELECT * FROM join_requests
            WHERE channel_id = #{channelId}
            <if test="status != null">AND status = #{status}</if>
            <if test="cursorId != null">AND id &lt; #{cursorId}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<JoinRequest> selectManageList(@Param("channelId") String channelId,
                                       @Param("status") String status,
                                       @Param("cursorId") String cursorId,
                                       @Param("limit") int limit);
}
