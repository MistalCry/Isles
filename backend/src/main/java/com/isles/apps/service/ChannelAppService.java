package com.isles.apps.service;

import com.isles.apps.dto.AppDtos;
import com.isles.apps.entity.ChannelApp;
import com.isles.apps.mapper.ChannelAppMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.time.ClockTimes;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 频道应用入口（V10 / docs/05 §2.14）：
 * 频道页展示的应用卡片（语音房 / 直播间 / 自定义），支持排序与启用开关。
 *
 * <p>读：频道可见即可（只返回启用项）；写：{@code channel.settings} 权限点。
 */
@Service
@RequiredArgsConstructor
public class ChannelAppService {

    private static final Logger log = LoggerFactory.getLogger(ChannelAppService.class);

    /** 单频道应用上限：超过后频道页放不下，也避免刷屏。 */
    private static final int MAX_APPS_PER_CHANNEL = 20;

    private final ChannelAppMapper appMapper;
    private final ChannelAccessService accessService;

    public List<AppDtos.AppView> list(String channelId, String viewerId, boolean includeDisabled) {
        accessService.requireVisibleChannel(channelId, viewerId);
        if (includeDisabled) {
            // 管理端要看被停用的应用，必须频道管理权限
            accessService.requirePermission(channelId, viewerId, ChannelPermissions.CHANNEL_SETTINGS);
        }
        return appMapper.selectByChannel(channelId).stream()
                .filter(app -> includeDisabled || Boolean.TRUE.equals(app.getEnabled()))
                .map(this::toView)
                .toList();
    }

    @Transactional
    public AppDtos.AppView create(String channelId, AppDtos.SaveAppRequest request, String userId) {
        accessService.requirePermission(channelId, userId, ChannelPermissions.CHANNEL_SETTINGS);
        if (appMapper.countByChannel(channelId) >= MAX_APPS_PER_CHANNEL) {
            throw new AppException(ErrorCode.CHANNEL_APP_LIMIT_EXCEEDED,
                    "频道应用最多 " + MAX_APPS_PER_CHANNEL + " 个");
        }
        LocalDateTime now = ClockTimes.nowUtc();
        ChannelApp app = new ChannelApp();
        app.setId(UlidGenerator.generate());
        app.setChannelId(channelId);
        applyEditableFields(app, request);
        app.setCreatedBy(userId);
        app.setCreatedAt(now);
        app.setUpdatedAt(now);
        appMapper.insert(app);
        log.info("频道应用：创建 channelId={} appId={} type={}", channelId, app.getId(), app.getAppType());
        return toView(app);
    }

    @Transactional
    public AppDtos.AppView update(String channelId, String appId, AppDtos.SaveAppRequest request, String userId) {
        accessService.requirePermission(channelId, userId, ChannelPermissions.CHANNEL_SETTINGS);
        ChannelApp app = requireApp(channelId, appId);
        applyEditableFields(app, request);
        app.setUpdatedAt(ClockTimes.nowUtc());
        appMapper.updateById(app);
        return toView(app);
    }

    @Transactional
    public void delete(String channelId, String appId, String userId) {
        accessService.requirePermission(channelId, userId, ChannelPermissions.CHANNEL_SETTINGS);
        ChannelApp app = requireApp(channelId, appId);
        appMapper.deleteById(app.getId());
        log.info("频道应用：删除 channelId={} appId={}", channelId, appId);
    }

    /**
     * 批量重排：按请求数组顺序写 0..n-1；只接受本频道的应用，越权 id 直接忽略。
     */
    @Transactional
    public List<AppDtos.AppView> reorder(String channelId, AppDtos.ReorderRequest request, String userId) {
        accessService.requirePermission(channelId, userId, ChannelPermissions.CHANNEL_SETTINGS);
        List<ChannelApp> existing = appMapper.selectByChannel(channelId);
        List<String> owned = existing.stream().map(ChannelApp::getId).toList();
        int index = 0;
        for (String appId : request.appIds()) {
            if (appId == null || !owned.contains(appId)) {
                continue;
            }
            appMapper.updateSortOrder(channelId, appId, index++);
        }
        return appMapper.selectByChannel(channelId).stream().map(this::toView).toList();
    }

    private ChannelApp requireApp(String channelId, String appId) {
        ChannelApp app = appId == null ? null : appMapper.selectById(appId);
        if (app == null || !channelId.equals(app.getChannelId())) {
            throw new AppException(ErrorCode.CHANNEL_APP_NOT_FOUND);
        }
        return app;
    }

    private void applyEditableFields(ChannelApp app, AppDtos.SaveAppRequest request) {
        app.setAppType(request.appType());
        app.setName(request.name().trim());
        app.setIconUrl(blankToNull(request.iconUrl()));
        app.setDescription(blankToNull(request.description()));
        app.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        app.setEnabled(request.enabled() == null || request.enabled());
    }

    private AppDtos.AppView toView(ChannelApp app) {
        return new AppDtos.AppView(app.getId(), app.getChannelId(), app.getAppType(), app.getName(),
                app.getIconUrl(), app.getDescription(), app.getSortOrder() == null ? 0 : app.getSortOrder(),
                Boolean.TRUE.equals(app.getEnabled()), ClockTimes.toInstant(app.getCreatedAt()));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
