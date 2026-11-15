package com.isles.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.ai.entity.ChannelAiSetting;
import org.apache.ibatis.annotations.Mapper;

/**
 * 频道助手设置数据访问（一个频道一条，channel_id 唯一）。
 */
@Mapper
public interface ChannelAiSettingMapper extends BaseMapper<ChannelAiSetting> {
}
