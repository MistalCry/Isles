package com.isles.manage.service;

import com.isles.channel.audit.AuditLogService;
import com.isles.channel.audit.ChannelAuditLog;
import com.isles.channel.entity.Channel;
import com.isles.channel.entity.ChannelInvite;
import com.isles.channel.entity.ChannelMember;
import com.isles.channel.entity.JoinRequest;
import com.isles.channel.mapper.ChannelInviteMapper;
import com.isles.channel.mapper.JoinRequestMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.channel.service.ChannelService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.pagination.CursorUtils;
import com.isles.common.pagination.PageResult;
import com.isles.common.time.ClockTimes;
import com.isles.manage.dto.InviteResponse;
import com.isles.manage.dto.JoinRequestResponse;
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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 邀请链接管理 + 加入审批（FR-CH-15/16）：列表/撤销邀请链接、审批列表、通过/拒绝。
 *
 * <p>通过审批时按需占用邀请链接次数并创建成员关系；拒绝可填写原因并通知申请人。
 */
@Service
@RequiredArgsConstructor
public class JoinManageService {

    private static final Logger log = LoggerFactory.getLogger(JoinManageService.class);

    private final ChannelInviteMapper channelInviteMapper;
    private final JoinRequestMapper joinRequestMapper;
    private final ChannelAccessService accessService;
    private final ChannelService channelService;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final UserMapper userMapper;

    /**
     * 邀请链接管理列表（不返回 token 明文）。
     */
    public PageResult<InviteResponse> listInvites(String channelId, String cursor, Integer limit, String actorId) {
        accessService.requirePermission(channelId, actorId, ChannelPermissions.INVITE_CREATE);
        int pageSize = CursorUtils.normalizeLimit(limit);
        String cursorId = CursorUtils.validateIdCursor(cursor);
        List<ChannelInvite> rows = channelInviteMapper.selectManageList(channelId, cursorId, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<ChannelInvite> page = hasMore ? rows.subList(0, pageSize) : rows;
        LocalDateTime now = ClockTimes.nowUtc();
        List<InviteResponse> items = page.stream().map(invite -> toInviteResponse(invite, now)).toList();
        String nextCursor = hasMore && !items.isEmpty() ? inviteId(items.get(items.size() - 1)) : null;
        return new PageResult<>(items, nextCursor);
    }

    /**
     * 撤销邀请链接（幂等）。
     */
    @Transactional
    public void revokeInvite(String channelId, String inviteId, String actorId) {
        accessService.requirePermission(channelId, actorId, ChannelPermissions.INVITE_CREATE);
        ChannelInvite invite = channelInviteMapper.selectById(inviteId);
        if (invite == null || !channelId.equals(invite.getChannelId())) {
            throw new AppException(ErrorCode.INVITE_NOT_FOUND);
        }
        if (invite.getRevokedAt() == null) {
            channelInviteMapper.revoke(inviteId);
            auditLogService.record(channelId, actorId, ChannelAuditLog.Action.INVITE_REVOKE, "INVITE", inviteId);
            log.info("撤销邀请链接 channelId={} inviteId={}", channelId, inviteId);
        }
    }

    /**
     * 加入审批列表。
     */
    public PageResult<JoinRequestResponse> listJoinRequests(String channelId, String status, String cursor,
                                                            Integer limit, String actorId) {
        accessService.requirePermission(channelId, actorId, ChannelPermissions.INVITE_APPROVE);
        int pageSize = CursorUtils.normalizeLimit(limit);
        String cursorId = CursorUtils.validateIdCursor(cursor);
        String statusValue = status == null || status.isBlank() ? null : status;
        if (statusValue != null && !List.of(JoinRequest.Status.PENDING.name(), JoinRequest.Status.APPROVED.name(),
                JoinRequest.Status.REJECTED.name()).contains(statusValue)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "status 取值非法");
        }
        List<JoinRequest> rows = joinRequestMapper.selectManageList(channelId, statusValue, cursorId, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<JoinRequest> page = hasMore ? rows.subList(0, pageSize) : rows;
        Map<String, User> users = loadUsers(page);
        List<JoinRequestResponse> items = page.stream()
                .map(request -> toJoinRequestResponse(request, users))
                .toList();
        String nextCursor = hasMore && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
        return new PageResult<>(items, nextCursor);
    }

    /**
     * 通过加入申请：占用邀请次数 + 创建成员关系 + 通知申请人。
     */
    @Transactional
    public JoinRequestResponse approve(String channelId, String requestId, String actorId) {
        accessService.requirePermission(channelId, actorId, ChannelPermissions.INVITE_APPROVE);
        JoinRequest request = requirePendingRequest(channelId, requestId);
        Channel channel = accessService.requireActiveChannel(channelId);
        ChannelMember member = accessService.findMember(channelId, request.getUserId());
        if (!ChannelAccessService.isActiveMember(member)) {
            if (request.getInviteId() != null
                    && channelInviteMapper.incrementUsedCount(request.getInviteId()) == 0) {
                throw new AppException(ErrorCode.INVITE_EXHAUSTED);
            }
            channelService.addMemberDirectly(channelId, request.getUserId());
        }
        LocalDateTime now = ClockTimes.nowUtc();
        request.setStatus(JoinRequest.Status.APPROVED.name());
        request.setReviewedBy(actorId);
        request.setReviewedAt(now);
        request.setReviewNote(null);
        joinRequestMapper.updateById(request);
        notificationService.notifySystem(request.getUserId(), Notification.Type.JOIN_APPROVED, channelId, null, null,
                "加入申请已通过", "你已加入频道「" + channel.getName() + "」", Map.of("channelId", channelId));
        auditLogService.record(channelId, actorId, ChannelAuditLog.Action.JOIN_APPROVE, "JOIN_REQUEST", requestId,
                Map.of("userId", request.getUserId()));
        log.info("通过加入申请 channelId={} requestId={} userId={}", channelId, requestId, request.getUserId());
        return toJoinRequestResponse(request, loadUsers(List.of(request)));
    }

    /**
     * 拒绝加入申请（可填写原因）并通知申请人。
     */
    @Transactional
    public JoinRequestResponse reject(String channelId, String requestId, String note, String actorId) {
        accessService.requirePermission(channelId, actorId, ChannelPermissions.INVITE_APPROVE);
        JoinRequest request = requirePendingRequest(channelId, requestId);
        Channel channel = accessService.requireActiveChannel(channelId);
        request.setStatus(JoinRequest.Status.REJECTED.name());
        request.setReviewedBy(actorId);
        request.setReviewedAt(ClockTimes.nowUtc());
        request.setReviewNote(note);
        joinRequestMapper.updateById(request);
        notificationService.notifySystem(request.getUserId(), Notification.Type.JOIN_REJECTED, channelId, null, null,
                "加入申请未通过", "你申请加入频道「" + channel.getName() + "」未通过"
                        + (note == null || note.isBlank() ? "" : "，原因：" + note),
                Map.of("channelId", channelId));
        auditLogService.record(channelId, actorId, ChannelAuditLog.Action.JOIN_REJECT, "JOIN_REQUEST", requestId,
                Map.of("userId", request.getUserId(), "note", note == null ? "" : note));
        log.info("拒绝加入申请 channelId={} requestId={} userId={}", channelId, requestId, request.getUserId());
        return toJoinRequestResponse(request, loadUsers(List.of(request)));
    }

    private JoinRequest requirePendingRequest(String channelId, String requestId) {
        JoinRequest request = joinRequestMapper.selectById(requestId);
        if (request == null || !channelId.equals(request.getChannelId())) {
            throw new AppException(ErrorCode.JOIN_REQUEST_NOT_FOUND);
        }
        if (!JoinRequest.Status.PENDING.name().equals(request.getStatus())) {
            throw new AppException(ErrorCode.JOIN_REQUEST_HANDLED);
        }
        return request;
    }

    private Map<String, User> loadUsers(List<JoinRequest> requests) {
        List<String> userIds = requests.stream()
                .flatMap(request -> java.util.stream.Stream.of(request.getUserId(), request.getReviewedBy()))
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private JoinRequestResponse toJoinRequestResponse(JoinRequest request, Map<String, User> users) {
        User applicant = users.get(request.getUserId());
        User reviewer = request.getReviewedBy() == null ? null : users.get(request.getReviewedBy());
        return new JoinRequestResponse(
                request.getId(),
                request.getChannelId(),
                request.getUserId(),
                applicant == null ? null : applicant.getUsername(),
                applicant == null ? null : applicant.getNickname(),
                applicant == null ? null : applicant.getAvatarUrl(),
                request.getInviteId(),
                request.getStatus(),
                request.getReviewedBy(),
                reviewer == null ? null : reviewer.getNickname(),
                ClockTimes.toInstant(request.getReviewedAt()),
                request.getReviewNote(),
                ClockTimes.toInstant(request.getCreatedAt()));
    }

    private InviteResponse toInviteResponse(ChannelInvite invite, LocalDateTime now) {
        boolean revoked = invite.getRevokedAt() != null;
        boolean expired = invite.getExpiresAt() != null && !invite.getExpiresAt().isAfter(now);
        boolean exhausted = invite.getMaxUses() != null && invite.getUsedCount() != null
                && invite.getUsedCount() >= invite.getMaxUses();
        String status = revoked ? "REVOKED" : expired ? "EXPIRED" : exhausted ? "EXHAUSTED" : "ACTIVE";
        return new InviteResponse(
                invite.getId(),
                invite.getChannelId(),
                Boolean.TRUE.equals(invite.getRequiresApproval()),
                ClockTimes.toInstant(invite.getExpiresAt()),
                invite.getMaxUses(),
                invite.getUsedCount(),
                revoked,
                status,
                invite.getCreatedBy(),
                ClockTimes.toInstant(invite.getCreatedAt()));
    }

    private String inviteId(InviteResponse response) {
        return response.id();
    }
}
