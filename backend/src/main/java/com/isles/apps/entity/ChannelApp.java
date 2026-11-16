package com.isles.apps.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 频道应用入口（表 {@code channel_apps}，V10 / docs/06 §13）。
 *
 * <p>频道页按 {@code sortOrder} 展示应用卡片；{@code enabled=false} 时前台隐藏但保留配置。
 */
@Getter
@Setter
@TableName("channel_apps")
public class ChannelApp {

    @TableId(type = IdType.INPUT)
    private String id;
    private String channelId;
    private String appType;
    private String name;
    private String iconUrl;
    private String description;
    private Integer sortOrder;
    private Boolean enabled;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum AppType {
        /** 语音房：可开房、上麦、实时语音。 */
        VOICE_ROOM,
        /** 直播间：预留（当前只做入口与占位）。 */
        LIVE_ROOM,
        /** 自定义入口：跳外链或纯展示。 */
        CUSTOM
    }
}
