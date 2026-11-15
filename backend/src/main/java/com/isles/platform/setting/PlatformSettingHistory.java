package com.isles.platform.setting;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 配置变更历史（表 {@code platform_setting_histories}，docs/11 §4.1）。
 *
 * <p>只追加：每次写入/重置都留一条，支持追责与回滚定位；不做更新与删除。
 */
@Getter
@Setter
@TableName("platform_setting_histories")
public class PlatformSettingHistory {

    @TableId(type = IdType.INPUT)
    private String id;
    private String settingKey;
    private String oldValue;
    private String newValue;
    private String actorId;
    private String ip;
    private LocalDateTime createdAt;
}
