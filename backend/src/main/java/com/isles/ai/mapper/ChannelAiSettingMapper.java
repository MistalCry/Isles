package com.isles.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.ai.entity.ChannelAiSetting;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 频道助手设置数据访问（一个频道一条，channel_id 唯一）。
 */
@Mapper
public interface ChannelAiSettingMapper extends BaseMapper<ChannelAiSetting> {

    /**
     * 定时任务可扫描的设置：**只取频道仍存活（ACTIVE 且未软删）的记录**（第 21 轮修正）。
     *
     * <p>旧实现只按 {@code enabled=1} 取，频道解散后设置行仍在，任务照样每天执行——
     * 先调用模型烧掉 token，再在发帖处撞 `CHANNEL_NOT_FOUND`，日志里堆满无意义的 FAILED。
     */
    @Select("""
            SELECT s.* FROM channel_ai_settings s
            JOIN channels c ON c.id = s.channel_id
            WHERE s.enabled = 1
              AND c.status = 'ACTIVE' AND c.deleted_at IS NULL
            """)
    List<ChannelAiSetting> selectScannable();

    /**
     * 关闭某频道的助手开关（频道解散时调用）：只改 enabled，保留人设与历史任务日志。
     */
    @Update("""
            UPDATE channel_ai_settings SET enabled = 0
            WHERE channel_id = #{channelId}
            """)
    int disableByChannel(@Param("channelId") String channelId);
}
