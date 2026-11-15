package com.isles.manage.dto;

import com.isles.channel.dto.ChannelResponse;

import java.time.Instant;
import java.util.List;

/**
 * 频道设置面板数据（GET /channels/{id}/settings）：频道信息 + 全员禁言 + 当前用户权限点。
 */
public record ChannelSettingsResponse(
        ChannelResponse channel,
        Instant muteAllUntil,
        boolean muteAllActive,
        List<String> myPermissions) {
}
