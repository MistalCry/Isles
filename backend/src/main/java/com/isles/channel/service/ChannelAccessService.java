package com.isles.channel.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.channel.entity.Channel;
import com.isles.channel.entity.ChannelMember;
import com.isles.channel.entity.Role;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.mapper.ChannelMemberMapper;
import com.isles.channel.mapper.RoleMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.time.ClockTimes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 频道访问控制统一入口（docs/03 §5.2、docs/07 §1/§3）：所有频道/帖子/评论操作必须先经过本服务。
 *
 * <p>判定顺序：频道存在且未解散 → 可见性（游客/成员）→ 成员身份 → 权限点 → 业务状态（禁言）。
 * 不可见与不存在统一返回 404，避免泄露私密频道是否存在（防 IDOR）。
 */
@Service
@RequiredArgsConstructor
public class ChannelAccessService {

    private final ChannelMapper channelMapper;
    private final ChannelMemberMapper channelMemberMapper;
    private final RoleMapper roleMapper;

    /**
     * 访问上下文：频道 + 当前成员 + 身份组（游客/非成员时后两者为 null）。
     */
    public record ChannelAccessContext(Channel channel, ChannelMember member, Role role) {

        public boolean isMember() {
            return isActiveMember(member);
        }
    }

    /**
     * 是否为有效成员：ACTIVE 或 MUTED 都仍属于频道；LEFT/KICKED/null 不是。
     */
    public static boolean isActiveMember(ChannelMember member) {
        if (member == null) {
            return false;
        }
        return ChannelMember.Status.ACTIVE.name().equals(member.getStatus())
                || ChannelMember.Status.MUTED.name().equals(member.getStatus());
    }

    /**
     * 频道必须存在且为 ACTIVE（不校验可见性，供邀请加入等场景使用）。
     */
    public Channel requireActiveChannel(String channelId) {
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null
                || channel.getDeletedAt() != null
                || !Channel.Status.ACTIVE.name().equals(channel.getStatus())) {
            throw new AppException(ErrorCode.CHANNEL_NOT_FOUND);
        }
        return channel;
    }

    /**
     * 校验可见性：公开频道 / 公开可见频道对游客开放；仅成员可见频道要求 ACTIVE 成员。
     * 不可见统一返回 404（不泄露存在性）。
     */
    public Channel requireVisibleChannel(String channelId, String viewerId) {
        Channel channel = requireActiveChannel(channelId);
        if (isPubliclyVisible(channel)) {
            return channel;
        }
        ChannelMember member = findMember(channelId, viewerId);
        if (!isActiveMember(member)) {
            throw new AppException(ErrorCode.CHANNEL_NOT_FOUND);
        }
        return channel;
    }

    public boolean isPubliclyVisible(Channel channel) {
        return Channel.Type.PUBLIC.name().equals(channel.getType())
                || Channel.Visibility.PUBLIC_VISIBLE.name().equals(channel.getVisibility());
    }

    /**
     * 查询成员关系（包含 LEFT/KICKED 等非 ACTIVE 状态，用于重复加入场景）。
     */
    public ChannelMember findMember(String channelId, String userId) {
        if (channelId == null || userId == null) {
            return null;
        }
        return channelMemberMapper.selectOne(Wrappers.<ChannelMember>lambdaQuery()
                .eq(ChannelMember::getChannelId, channelId)
                .eq(ChannelMember::getUserId, userId));
    }

    public Role findRole(String channelId, String roleId) {
        if (roleId == null) {
            return null;
        }
        Role role = roleMapper.selectById(roleId);
        if (role == null || !channelId.equals(role.getChannelId())) {
            return null;
        }
        return role;
    }

    /**
     * 要求 ACTIVE 成员身份，并返回访问上下文。
     */
    public ChannelAccessContext requireMemberContext(String channelId, String userId) {
        Channel channel = requireActiveChannel(channelId);
        ChannelMember member = findMember(channelId, userId);
        if (!isActiveMember(member)) {
            throw new AppException(ErrorCode.NOT_JOINED);
        }
        return new ChannelAccessContext(channel, member, findRole(channelId, member.getRoleId()));
    }

    /**
     * 发帖 / 评论权限校验：成员身份 + 禁言状态 + 权限点。
     */
    public ChannelAccessContext requireInteractionPermission(String channelId, String userId, String permission) {
        ChannelAccessContext context = requireMemberContext(channelId, userId);
        ChannelMember member = context.member();
        if (isMuted(member)) {
            throw new AppException(ErrorCode.CHANNEL_MUTED);
        }
        if (!isOwner(context.channel(), userId) && !hasPermission(context.role(), permission)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return context;
    }

    /**
     * 管理操作权限校验（邀请链接、内容管理等）：成员身份 + 权限点（owner 始终放行）。
     */
    public ChannelAccessContext requirePermission(String channelId, String userId, String permission) {
        ChannelAccessContext context = requireMemberContext(channelId, userId);
        if (!isOwner(context.channel(), userId) && !hasPermission(context.role(), permission)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return context;
    }

    public boolean isOwner(Channel channel, String userId) {
        return userId != null && userId.equals(channel.getOwnerId());
    }

    /**
     * 判断身份组是否具备权限点。permissions 为 JSON 数组字符串，仅由服务端写入，M1 用精确包含判断即可。
     */
    public boolean hasPermission(Role role, String permission) {
        if (role == null || role.getPermissions() == null || permission == null) {
            return false;
        }
        return role.getPermissions().contains("\"" + permission + "\"");
    }

    /**
     * 禁言判断：个人禁言状态或禁言到期时间未到。
     */
    public boolean isMuted(ChannelMember member) {
        if (member == null) {
            return false;
        }
        if (ChannelMember.Status.MUTED.name().equals(member.getStatus())) {
            return true;
        }
        LocalDateTime mutedUntil = member.getMutedUntil();
        return mutedUntil != null && mutedUntil.isAfter(ClockTimes.nowUtc());
    }
}
