package com.isles.apps.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.apps.entity.ChannelApp;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 频道应用数据访问（V10）。
 */
@Mapper
public interface ChannelAppMapper extends BaseMapper<ChannelApp> {

    /** 频道内全部应用，按 sort_order 升序。 */
    @Select("""
            SELECT * FROM channel_apps
            WHERE channel_id = #{channelId}
            ORDER BY sort_order ASC, id ASC
            """)
    List<ChannelApp> selectByChannel(@Param("channelId") String channelId);

    /** 频道内应用数量（上限校验）。 */
    @Select("SELECT COUNT(*) FROM channel_apps WHERE channel_id = #{channelId}")
    long countByChannel(@Param("channelId") String channelId);

    /** 排序：单条更新（重排时逐条执行，条数 ≤ 20，代价可忽略）。 */
    @Update("UPDATE channel_apps SET sort_order = #{sortOrder} WHERE id = #{id} AND channel_id = #{channelId}")
    int updateSortOrder(@Param("channelId") String channelId, @Param("id") String id,
                        @Param("sortOrder") int sortOrder);
}
