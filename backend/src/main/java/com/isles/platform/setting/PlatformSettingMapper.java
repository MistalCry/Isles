package com.isles.platform.setting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 平台配置数据访问。
 *
 * <p>写入采用「先查后写」而非 {@code INSERT ... ON DUPLICATE KEY UPDATE}：配置写入是低频后台操作，
 * 且需要拿到变更前的原值写历史；并发冲突由主键唯一约束兜底。
 */
@Mapper
public interface PlatformSettingMapper extends BaseMapper<PlatformSetting> {

    @Delete("DELETE FROM platform_settings WHERE setting_key = #{key}")
    int deleteByKey(@Param("key") String key);
}
