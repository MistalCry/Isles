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
import com.isles.common.json.JsonSupport;
import com.isles.common.time.ClockTimes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 频道访问控制统一入口（docs/03 §5.2、docs/07 §3）：所有频道/帖子/评论/管理操作必须先经过本服务。
 *
 * <p>判定顺序：频道存在且未解散 → 可见性（游客/成员）→ 成员身份 → 权限点（含板块范围）→
 * 业务状态（个人禁言/全员禁言）。不可见与不存在统一返回 404，避免泄露私密频道是否存在（防 IDOR）。
 */
@Service
@RequiredArgsConstructor
public class ChannelAccessService {

    private final ChannelMapper channelMapper;
    private final ChannelMemberMapper channelMemberMapper;
    private final RoleMapper roleMapper;
    private final JsonSupport jsonSupport;

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
     * 校验可见性：公开频道 / 公开可见频道对游客开放；仅成员可见频道要求有效成员。
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
     * 要求有效成员身份，并返回访问上下文。
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
     * 发帖 / 评论权限校验：成员身份 + 个人禁言 + 全员禁言 + 权限点。
     */
    public ChannelAccessContext requireInteractionPermission(String channelId, String userId, String permission) {
        ChannelAccessContext context = requireMemberContext(channelId, userId);
        ChannelMember member = context.member();
        if (isMuted(member)) {
            throw new AppException(ErrorCode.CHANNEL_MUTED);
        }
        if (isMuteAllActive(context.channel()) && !canSpeakDuringMuteAll(context.channel(), context.role(), userId)) {
            throw new AppException(ErrorCode.CHANNEL_MUTED, "频道全员禁言中，暂时无法发言");
        }
        if (!isOwner(context.channel(), userId) && !hasPermission(context.role(), permission)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return context;
    }

    /**
     * 管理操作权限校验（邀请链接、成员、内容等）：成员身份 + 权限点（owner 始终放行）。
     */
    public ChannelAccessContext requirePermission(String channelId, String userId, String permission) {
        ChannelAccessContext context = requireMemberContext(channelId, userId);
        if (!isOwner(context.channel(), userId) && !hasPermission(context.role(), permission)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return context;
    }

    /**
     * 任一权限点满足即可（如停用个人禁言或全员禁言）。
     */
    public ChannelAccessContext requireAnyPermission(String channelId, String userId, String... permissions) {
        ChannelAccessContext context = requireMemberContext(channelId, userId);
        if (isOwner(context.channel(), userId)) {
            return context;
        }
        for (String permission : permissions) {
            if (hasPermission(context.role(), permission)) {
                return context;
            }
        }
        throw new AppException(ErrorCode.FORBIDDEN);
    }

    /**
     * 仅频道主可执行（解散、转让等）。
     */
    public ChannelAccessContext requireOwner(String channelId, String userId) {
        ChannelAccessContext context = requireMemberContext(channelId, userId);
        if (!isOwner(context.channel(), userId)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return context;
    }

    /**
     * 内容管理权限（帖子置顶/精华/删除）：权限点 + 子板块管理员的范围限制。
     *
     * @param boardId 帖子所属板块；子板块管理员只能管理 scopeBoardId 指定板块
     */
    public ChannelAccessContext requirePostManagePermission(String channelId, String userId, String boardId,
                                                            String permission) {
        ChannelAccessContext context = requireMemberContext(channelId, userId);
        if (isOwner(context.channel(), userId)) {
            return context;
        }
        Role role = context.role();
        if (!hasPermission(role, permission)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        if (role.getScopeBoardId() != null && !role.getScopeBoardId().equals(boardId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "子板块管理员只能管理所属板块的内容");
        }
        return context;
    }

    /**
     * 板块级管理权限（板块管理/绑定板块管理员时使用）。
     */
    public ChannelAccessContext requireBoardManagePermission(String channelId, String userId, String boardId,
                                                             String permission) {
        return requirePostManagePermission(channelId, userId, boardId, permission);
    }

    public boolean isOwner(Channel channel, String userId) {
        return userId != null && userId.equals(channel.getOwnerId());
    }

    /**
     * 是否为频道管理者（owner 或任一管理权限持有者），用于前端/通知等展示。
     */
    public boolean isChannelAdmin(ChannelAccessContext context, String userId) {
        return context != null && (isOwner(context.channel(), userId)
                || hasPermission(context.role(), ChannelPermissions.CONTENT_MANAGE));
    }

    /**
     * 判断身份组是否具备权限点。permissions 为 JSON 数组字符串，仅由服务端写入。
     */
    public boolean hasPermission(Role role, String permission) {
        if (role == null || role.getPermissions() == null || permission == null) {
            return false;
        }
        return role.getPermissions().contains("\"" + permission + "\"");
    }

    /**
     * 当前用户在频道内的权限点清单（owner 返回全量）。前端据此隐藏/禁用按钮。
     */
    public List<String> permissionsOf(String channelId, String userId) {
        if (userId == null) {
            return List.of();
        }
        Channel channel = channelMapper.selectById(channelId);
        if (channel == null || isOwner(channel, userId)) {
            return ChannelPermissions.ALL;
        }
        ChannelMember member = findMember(channelId, userId);
        if (!isActiveMember(member)) {
            return List.of();
        }
        Role role = findRole(channelId, member.getRoleId());
        return parsePermissions(role);
    }

    /**
     * 解析身份组权限 JSON 数组；数据异常时返回空列表而非抛错（避免管理页整体不可用）。
     */
    public List<String> parsePermissions(Role role) {
        if (role == null || role.getPermissions() == null || role.getPermissions().isBlank()) {
            return List.of();
        }
        try {
            String[] permissions = jsonSupport.fromJson(role.getPermissions(), String[].class);
            return List.of(permissions);
        } catch (RuntimeException ex) {
            return List.of();
        }
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

    /**
     * 全员禁言是否生效。
     */
    public boolean isMuteAllActive(Channel channel) {
        LocalDateTime muteAllUntil = channel == null ? null : channel.getMuteAllUntil();
        return muteAllUntil != null && muteAllUntil.isAfter(ClockTimes.nowUtc());
    }

    /**
     * 全员禁言期间可发言身份：频道主或具备 channel.mute_all 权限（超级管理员）。
     */
    private boolean canSpeakDuringMuteAll(Channel channel, Role role, String userId) {
        return isOwner(channel, userId) || hasPermission(role, ChannelPermissions.MUTE_ALL);
    }
}
