package com.isles.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.ai.entity.AiModelConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 模型配置数据访问（常规 CRUD 交给 BaseMapper）。
 */
@Mapper
public interface AiModelConfigMapper extends BaseMapper<AiModelConfig> {
}
