package com.isles.manage.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.channel.audit.AuditLogService;
import com.isles.channel.audit.ChannelAuditLog;
import com.isles.channel.entity.Channel;
import com.isles.channel.entity.ChannelMember;
import com.isles.channel.entity.Role;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.mapper.ChannelMemberMapper;
import com.isles.channel.mapper.RoleMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.channel.service.SystemRoles;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.pagination.CursorUtils;
import com.isles.common.pagination.OffsetPage;
import com.isles.common.pagination.PageResult;
import com.isles.common.time.ClockTimes;
import com.isles.manage.dto.MemberResponse;
import com.isles.manage.dto.MuteRequest;
import com.isles.notification.entity.Notification;
import com.isles.notification.service.NotificationService;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 成员管理（FR-MGR-09）：列表/搜索、个人禁言、移除成员、分配身份组。
 *
 * <p>层级保护：不能操作频道主；非频道主不能操作权限不低于自己的成员（docs/03 §2.3）。
 */
@Service
@RequiredArgsConstructor
public class MemberManageService {

    private static final Logger log = LoggerFactory.getLogger(MemberManageService.class);

    private final ChannelMapper channelMapper;
    private final ChannelMemberMapper channelMemberMapper;
    private final RoleMapper roleMapper;
    private final UserMapper userMapper;
    private final ChannelAccessService accessService;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;

    /**
     * 成员列表：搜索用户名/昵称、按身份组过滤，ULID 游标分页。
     */
    public PageResult<MemberResponse> list(String channelId, String keyword, String roleId, String cursor,
                                           Integer limit, String viewerId) {
        accessService.requireMemberContext(channelId, viewerId);
        int pageSize = CursorUtils.normalizeLimit(limit);
        String cursorId = CursorUtils.validateIdCursor(cursor);
        String pattern = keyword == null || keyword.isBlank()
                ? null
                : "%" + escapeLike(keyword.trim()) + "%";
        List<ChannelMember> rows = channelMemberMapper.selectManageList(channelId, pattern, roleId, cursorId,
                pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<ChannelMember> page = hasMore ? rows.subList(0, pageSize) : rows;

        Map<String, User> users = loadUsers(page);
        Map<String, Role> roles = loadRoles(page);
        List<MemberResponse> items = page.stream()
                .map(member -> toResponse(member, users.get(member.getUserId()),
                        roles.get(member.getRoleId())))
                .toList();
        String nextCursor = hasMore && !items.isEmpty() ? page.get(page.size() - 1).getId() : null;
        return new PageResult<>(items, nextCursor);
    }

    /**
     * 成员列表 - 管理端偏移分页（docs/05 §1）：返回 {@code items + total}，供成员管理表格分页。
     */
    public OffsetPage<MemberResponse> listOffset(String channelId, String keyword, String roleId, int page,
                                                 int size, String viewerId) {
        accessService.requireMemberContext(channelId, viewerId);
        int pageNumber = Math.max(1, page);
        int pageSize = Math.min(Math.max(1, size), 100);
        String pattern = keyword == null || keyword.isBlank()
                ? null
                : "%" + escapeLike(keyword.trim()) + "%";
        long total = channelMemberMapper.countManageList(channelId, pattern, roleId);
        List<ChannelMember> rows = channelMemberMapper.selectManageListOffset(channelId, pattern, roleId,
                (pageNumber - 1) * pageSize, pageSize);
        Map<String, User> users = loadUsers(rows);
        Map<String, Role> roles = loadRoles(rows);
        List<MemberResponse> items = rows.stream()
                .map(member -> toResponse(member, users.get(member.getUserId()),
                        roles.get(member.getRoleId())))
                .toList();
        return new OffsetPage<>(items, total);
    }

    /**
     * 个人禁言（channel.member.mute）。
     */
    @Transactional
    public MemberResponse mute(String channelId, String targetUserId, MuteRequest request, String actorId) {
        ChannelAccessService.ChannelAccessContext actorContext = accessService
                .requirePermission(channelId, actorId, ChannelPermissions.MEMBER_MUTE);
        Channel channel = actorContext.channel();
        ChannelMember target = requireManageableMember(channel, targetUserId, actorId);
        checkHierarchy(actorContext, target, actorId);
        long duration = requireDuration(request);
        LocalDateTime until = ClockTimes.nowUtc().plusSeconds(duration);
        target.setStatus(ChannelMember.Status.MUTED.name());
        target.setMutedUntil(until);
        target.setUpdatedAt(ClockTimes.nowUtc());
        channelMemberMapper.updateById(target);
        notificationService.notifySystem(targetUserId, Notification.Type.MEMBER_MUTED, channelId, null, null,
                "你已被禁言", "在频道「" + channel.getName() + "」中被禁言至 " + until, Map.of("until", until.toString()));
        auditLogService.record(channelId, actorId, ChannelAuditLog.Action.MEMBER_MUTE, "MEMBER", targetUserId,
                Map.of("until", until.toString(), "durationSeconds", duration));
        log.info("禁言成员 channelId={} target={} actor={}", channelId, targetUserId, actorId);
        Role role = roleMapper.selectById(target.getRoleId());
        return toResponse(target, userMapper.selectById(targetUserId), role);
    }

    /**
     * 解除个人禁言。
     */
    @Transactional
    public MemberResponse unmute(String channelId, String targetUserId, String actorId) {
        ChannelAccessService.ChannelAccessContext actorContext = accessService
                .requirePermission(channelId, actorId, ChannelPermissions.MEMBER_MUTE);
        Channel channel = actorContext.channel();
        ChannelMember target = requireManageableMember(channel, targetUserId, actorId);
        checkHierarchy(actorContext, target, actorId);
        target.setStatus(ChannelMember.Status.ACTIVE.name());
        target.setMutedUntil(null);
        target.setUpdatedAt(ClockTimes.nowUtc());
        channelMemberMapper.updateById(target);
        auditLogService.record(channelId, actorId, ChannelAuditLog.Action.MEMBER_UNMUTE, "MEMBER", targetUserId);
        Role role = roleMapper.selectById(target.getRoleId());
        return toResponse(target, userMapper.selectById(targetUserId), role);
    }

    /**
     * 移除成员（channel.member.remove）：保留成员记录用于历史统计。
     */
    @Transactional
    public void remove(String channelId, String targetUserId, String actorId) {
        ChannelAccessService.ChannelAccessContext actorContext = accessService
                .requirePermission(channelId, actorId, ChannelPermissions.MEMBER_REMOVE);
        Channel channel = actorContext.channel();
        ChannelMember target = requireManageableMember(channel, targetUserId, actorId);
        checkHierarchy(actorContext, target, actorId);
        target.setStatus(ChannelMember.Status.KICKED.name());
        target.setMutedUntil(null);
        target.setUpdatedAt(ClockTimes.nowUtc());
        channelMemberMapper.updateById(target);
        channelMapper.addMemberCount(channelId, -1);
        notificationService.notifySystem(targetUserId, Notification.Type.MEMBER_REMOVED, channelId, null, null,
                "你已被移出频道", "你已被移出频道「" + channel.getName() + "」", Map.of());
        auditLogService.record(channelId, actorId, ChannelAuditLog.Action.MEMBER_REMOVE, "MEMBER", targetUserId);
        log.info("移除成员 channelId={} target={} actor={}", channelId, targetUserId, actorId);
    }

    /**
     * 分配身份组（channel.role.manage）：不能分配频道主；不能授予超过自身的权限。
     */
    @Transactional
    public MemberResponse assignRole(String channelId, String targetUserId, String roleId, String actorId) {
        ChannelAccessService.ChannelAccessContext actorContext = accessService
                .requirePermission(channelId, actorId, ChannelPermissions.ROLE_MANAGE);
        Channel channel = actorContext.channel();
        ChannelMember target = requireManageableMember(channel, targetUserId, actorId);
        Role newRole = roleMapper.selectById(roleId);
        if (newRole == null || !channelId.equals(newRole.getChannelId())) {
            throw new AppException(ErrorCode.ROLE_NOT_FOUND);
        }
        if (SystemRoles.OWNER.equals(newRole.getName())) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "频道主身份不可通过接口分配");
        }
        checkHierarchy(actorContext, target, actorId);
        if (!accessService.isOwner(channel, actorId)) {
            List<String> actorPermissions = accessService.parsePermissions(actorContext.role());
            if (!actorPermissions.containsAll(accessService.parsePermissions(newRole))) {
                throw new AppException(ErrorCode.PERMISSION_EXCEEDED);
            }
        }
        Role oldRole = target.getRoleId() == null ? null : roleMapper.selectById(target.getRoleId());
        target.setRoleId(newRole.getId());
        target.setUpdatedAt(ClockTimes.nowUtc());
        channelMemberMapper.updateById(target);
        notificationService.notifySystem(targetUserId, Notification.Type.ROLE_CHANGED, channelId, null, null,
                "你的身份组已调整", "在频道「" + channel.getName() + "」中的身份组调整为「" + newRole.getName() + "」",
                Map.of("roleId", newRole.getId(), "roleName", newRole.getName()));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fromRoleId", oldRole == null ? null : oldRole.getId());
        detail.put("toRoleId", newRole.getId());
        auditLogService.record(channelId, actorId, ChannelAuditLog.Action.MEMBER_ROLE_CHANGE, "MEMBER", targetUserId,
                detail);
        log.info("调整成员身份组 channelId={} target={} roleId={}", channelId, targetUserId, roleId);
        return toResponse(target, userMapper.selectById(targetUserId), newRole);
    }

    /**
     * 校验目标成员可被管理：存在、非自己、非频道主。
     */
    private ChannelMember requireManageableMember(Channel channel, String targetUserId, String actorId) {
        if (targetUserId.equals(actorId)) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "不能对自己执行该操作");
        }
        if (targetUserId.equals(channel.getOwnerId())) {
            throw new AppException(ErrorCode.CANNOT_OPERATE_OWNER);
        }
        ChannelMember target = accessService.findMember(channel.getId(), targetUserId);
        if (target == null || !ChannelAccessService.isActiveMember(target)) {
            throw new AppException(ErrorCode.MEMBER_NOT_FOUND);
        }
        return target;
    }

    /**
     * 层级校验：非频道主不能操作超级管理员，或权限高于/等于自己的成员。
     */
    private void checkHierarchy(ChannelAccessService.ChannelAccessContext actorContext, ChannelMember target,
                                String actorId) {
        Channel channel = actorContext.channel();
        if (accessService.isOwner(channel, actorId)) {
            return;
        }
        Role targetRole = roleMapper.selectById(target.getRoleId());
        if (targetRole == null || SystemRoles.SUPER_ADMIN.equals(targetRole.getName())) {
            throw new AppException(ErrorCode.FORBIDDEN, "不能操作超级管理员");
        }
        List<String> actorPermissions = accessService.parsePermissions(actorContext.role());
        if (!actorPermissions.containsAll(accessService.parsePermissions(targetRole))) {
            throw new AppException(ErrorCode.PERMISSION_EXCEEDED);
        }
    }

    private long requireDuration(MuteRequest request) {
        Long duration = request.durationSeconds();
        if (duration == null || duration < 60 || duration > 2_592_000L) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "禁言时长需在 60 秒 – 30 天之间");
        }
        return duration;
    }

    private Map<String, User> loadUsers(List<ChannelMember> members) {
        List<String> userIds = members.stream().map(ChannelMember::getUserId).distinct().toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private Map<String, Role> loadRoles(List<ChannelMember> members) {
        Set<String> roleIds = members.stream().map(ChannelMember::getRoleId).collect(Collectors.toSet());
        roleIds.remove(null);
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        return roleMapper.selectBatchIds(roleIds).stream()
                .collect(Collectors.toMap(Role::getId, Function.identity()));
    }

    private MemberResponse toResponse(ChannelMember member, User user, Role role) {
        boolean muted = accessService.isMuted(member);
        return new MemberResponse(
                member.getUserId(),
                user == null ? null : user.getUsername(),
                user == null ? null : user.getNickname(),
                user == null ? null : user.getAvatarUrl(),
                member.getRoleId(),
                role == null ? null : role.getName(),
                member.getStatus(),
                ClockTimes.toInstant(member.getMutedUntil()),
                muted,
                ClockTimes.toInstant(member.getJoinedAt()));
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
