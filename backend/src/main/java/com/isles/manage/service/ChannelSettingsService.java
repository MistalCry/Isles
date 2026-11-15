package com.isles.manage.service;

import com.isles.channel.audit.AuditLogService;
import com.isles.channel.audit.ChannelAuditLog;
import com.isles.channel.dto.ChannelResponse;
import com.isles.channel.entity.Channel;
import com.isles.channel.entity.ChannelMember;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.mapper.ChannelMemberMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.channel.service.ChannelService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.time.ClockTimes;
import com.isles.manage.dto.ChannelSettingsResponse;
import com.isles.manage.dto.MuteRequest;
import com.isles.manage.dto.UpdateChannelRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 频道设置服务（FR-CH-10/11/12/14、FR-MGR-04）：
 * 频道资料修改、可见性、全员禁言、退出频道、解散频道；所有写操作写管理日志。
 */
@Service
@RequiredArgsConstructor
public class ChannelSettingsService {

    private static final Logger log = LoggerFactory.getLogger(ChannelSettingsService.class);

    private final ChannelMapper channelMapper;
    private final ChannelMemberMapper channelMemberMapper;
    private final ChannelAccessService accessService;
    private final ChannelService channelService;
    private final AuditLogService auditLogService;

    /**
     * 修改频道资料；可见性变更需要额外的 channel.visibility 权限。
     */
    @Transactional
    public ChannelResponse updateChannel(String channelId, UpdateChannelRequest request, String userId) {
        ChannelAccessService.ChannelAccessContext context = accessService.requireMemberContext(channelId, userId);
        Channel channel = context.channel();

        boolean profileChange = request.name() != null || request.avatarUrl() != null
                || request.coverUrl() != null || request.description() != null;
        boolean typeChange = request.type() != null && !request.type().equals(channel.getType());
        boolean visibilityChange = request.visibility() != null && !request.visibility().equals(channel.getVisibility());
        if (profileChange || typeChange) {
            accessService.requirePermission(channelId, userId, ChannelPermissions.CHANNEL_SETTINGS);
        }
        if (visibilityChange) {
            accessService.requirePermission(channelId, userId, ChannelPermissions.VISIBILITY);
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty()) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "频道名不能为空白字符");
            }
            detail.put("name", Map.of("before", channel.getName(), "after", name));
            channel.setName(name);
        }
        if (request.avatarUrl() != null) {
            String avatarUrl = request.avatarUrl().trim();
            if (avatarUrl.isEmpty()) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "头像地址不能为空");
            }
            detail.put("avatarUrl", avatarUrl);
            channel.setAvatarUrl(avatarUrl);
        }
        if (request.coverUrl() != null) {
            detail.put("coverUrl", request.coverUrl());
            channel.setCoverUrl(request.coverUrl());
        }
        if (request.description() != null) {
            detail.put("description", request.description());
            channel.setDescription(request.description());
        }
        if (request.type() != null) {
            if (!Channel.Type.PUBLIC.name().equals(request.type()) && !Channel.Type.PRIVATE.name().equals(request.type())) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "频道类型取值非法");
            }
            if (typeChange) {
                detail.put("type", Map.of("before", channel.getType(), "after", request.type()));
                channel.setType(request.type());
                // 私密频道默认仅成员可见（未显式指定可见性时）
                if (Channel.Type.PRIVATE.name().equals(request.type()) && request.visibility() == null) {
                    channel.setVisibility(Channel.Visibility.MEMBERS_ONLY.name());
                }
                // 公开频道按定义对所有人可见，避免残留 MEMBERS_ONLY 造成数据自相矛盾
                if (Channel.Type.PUBLIC.name().equals(request.type()) && request.visibility() == null
                        && !Channel.Visibility.PUBLIC_VISIBLE.name().equals(channel.getVisibility())) {
                    detail.put("visibility", Map.of("before", channel.getVisibility(),
                            "after", Channel.Visibility.PUBLIC_VISIBLE.name()));
                    channel.setVisibility(Channel.Visibility.PUBLIC_VISIBLE.name());
                }
            }
        }
        if (request.visibility() != null) {
            if (!Channel.Visibility.PUBLIC_VISIBLE.name().equals(request.visibility())
                    && !Channel.Visibility.MEMBERS_ONLY.name().equals(request.visibility())) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "可见性取值非法");
            }
            if (visibilityChange) {
                detail.put("visibility", Map.of("before", channel.getVisibility(), "after", request.visibility()));
                channel.setVisibility(request.visibility());
            }
        }
        if (detail.isEmpty()) {
            return channelService.getChannel(channelId, userId);
        }
        channel.setUpdatedAt(ClockTimes.nowUtc());
        channelMapper.updateById(channel);
        auditLogService.record(channelId, userId, ChannelAuditLog.Action.CHANNEL_SETTINGS, "CHANNEL", channelId, detail);
        log.info("频道资料更新 channelId={} actorId={}", channelId, userId);
        return channelService.getChannel(channelId, userId);
    }

    /**
     * 频道设置面板数据：频道信息 + 全员禁言状态 + 当前用户权限点。
     */
    public ChannelSettingsResponse getSettings(String channelId, String userId) {
        ChannelAccessService.ChannelAccessContext context = accessService.requireMemberContext(channelId, userId);
        ChannelResponse channel = channelService.getChannel(channelId, userId);
        return new ChannelSettingsResponse(
                channel,
                ClockTimes.toInstant(context.channel().getMuteAllUntil()),
                accessService.isMuteAllActive(context.channel()),
                accessService.permissionsOf(channelId, userId));
    }

    /**
     * 开启全员禁言：仅频道主/超级管理员（channel.mute_all）。
     */
    @Transactional
    public ChannelSettingsResponse muteAll(String channelId, MuteRequest request, String userId) {
        ChannelAccessService.ChannelAccessContext context = accessService
                .requirePermission(channelId, userId, ChannelPermissions.MUTE_ALL);
        Channel channel = context.channel();
        long duration = requireDuration(request);
        LocalDateTime until = ClockTimes.nowUtc().plusSeconds(duration);
        channel.setMuteAllUntil(until);
        channel.setUpdatedAt(ClockTimes.nowUtc());
        channelMapper.updateById(channel);
        auditLogService.record(channelId, userId, ChannelAuditLog.Action.CHANNEL_MUTE_ALL, "CHANNEL", channelId,
                Map.of("until", until.toString(), "durationSeconds", duration));
        log.info("开启全员禁言 channelId={} until={}", channelId, until);
        return new ChannelSettingsResponse(channelService.getChannel(channelId, userId),
                ClockTimes.toInstant(until), true, accessService.permissionsOf(channelId, userId));
    }

    /**
     * 取消全员禁言。
     */
    @Transactional
    public ChannelSettingsResponse cancelMuteAll(String channelId, String userId) {
        ChannelAccessService.ChannelAccessContext context = accessService
                .requirePermission(channelId, userId, ChannelPermissions.MUTE_ALL);
        Channel channel = context.channel();
        channel.setMuteAllUntil(null);
        channel.setUpdatedAt(ClockTimes.nowUtc());
        channelMapper.updateById(channel);
        auditLogService.record(channelId, userId, ChannelAuditLog.Action.CHANNEL_UNMUTE_ALL, "CHANNEL", channelId);
        return new ChannelSettingsResponse(channelService.getChannel(channelId, userId), null, false,
                accessService.permissionsOf(channelId, userId));
    }

    /**
     * 退出频道：频道主必须转让或解散（docs/03 FR-CH-12）。
     */
    @Transactional
    public void leaveChannel(String channelId, String userId) {
        ChannelAccessService.ChannelAccessContext context = accessService.requireMemberContext(channelId, userId);
        if (accessService.isOwner(context.channel(), userId)) {
            throw new AppException(ErrorCode.OWNER_CANNOT_LEAVE);
        }
        ChannelMember member = context.member();
        member.setStatus(ChannelMember.Status.LEFT.name());
        member.setMutedUntil(null);
        member.setUpdatedAt(ClockTimes.nowUtc());
        channelMemberMapper.updateById(member);
        channelMapper.addMemberCount(channelId, -1);
        auditLogService.record(channelId, userId, ChannelAuditLog.Action.MEMBER_LEAVE, "MEMBER", userId);
        log.info("退出频道 channelId={} userId={}", channelId, userId);
    }

    /**
     * 解散频道：仅频道主（channel.dissolve）。
     */
    @Transactional
    public void dissolveChannel(String channelId, String userId) {
        ChannelAccessService.ChannelAccessContext context = accessService.requireOwner(channelId, userId);
        Channel channel = context.channel();
        channel.setStatus(Channel.Status.DISSOLVED.name());
        channel.setDeletedAt(ClockTimes.nowUtc());
        channel.setUpdatedAt(ClockTimes.nowUtc());
        channelMapper.updateById(channel);
        auditLogService.record(channelId, userId, ChannelAuditLog.Action.CHANNEL_DISSOLVE, "CHANNEL", channelId);
        log.info("解散频道 channelId={} ownerId={}", channelId, userId);
    }

    private long requireDuration(MuteRequest request) {
        Long duration = request.durationSeconds();
        if (duration == null || duration < 60 || duration > 2_592_000L) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "禁言时长需在 60 秒 – 30 天之间");
        }
        return duration;
    }
}
