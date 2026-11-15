package com.isles.platform.setting;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 运行时配置覆盖值（表 {@code platform_settings}，docs/11 §4.1）。
 *
 * <p>只存被后台覆盖过的键；未覆盖时读取 {@link SettingDefinitions} 的默认值。
 */
@Getter
@Setter
@TableName("platform_settings")
public class PlatformSetting {

    @TableId(value = "setting_key", type = IdType.INPUT)
    private String settingKey;
    private String settingValue;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
