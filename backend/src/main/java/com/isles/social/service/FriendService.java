package com.isles.social.service;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.pagination.CursorUtils;
import com.isles.common.pagination.PageResult;
import com.isles.common.time.ClockTimes;
import com.isles.notification.entity.Notification;
import com.isles.notification.service.NotificationService;
import com.isles.realtime.RealtimeDispatch;
import com.isles.realtime.RealtimePublisher;
import com.isles.realtime.dto.NotificationPush;
import com.isles.realtime.dto.RealtimeEvent;
import com.isles.social.dto.BlockedUserResponse;
import com.isles.social.dto.FriendRequestResponse;
import com.isles.social.dto.FriendResponse;
import com.isles.social.dto.FriendStatusResponse;
import com.isles.social.dto.FriendUserBrief;
import com.isles.social.entity.FriendRelation;
import com.isles.social.mapper.FriendRelationMapper;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import com.isles.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 好友服务（docs/03 FR-USER-05、§11 决策 5；V4 {@code friend_relations}）。
 *
 * <h3>状态机（有向行模型）</h3>
 * <pre>
 * 无关系 --申请--> PENDING --同意--> ACCEPTED（双向两行）
 *                       --拒绝--> REJECTED --重新申请--> PENDING
 * 任意状态 --拉黑--> BLOCKED（仅拉黑方持有；清理双方非拉黑关系）
 * BLOCKED --取消拉黑--> 无关系
 * </pre>
 * 防重复申请：同一对用户同方向只允许一条 {@code PENDING}；互相申请时直接升级为好友。
 * 被拉黑方不可发起好友申请/私信（{@link #assertCanMessage} 供私信模块复用）。
 *
 * <p>通知：申请/处理结果复写 {@link NotificationService} 落库（type=SYSTEM，notification 模块暂未提供好友类型），
 * 并通过 {@link RealtimePublisher} 推送 {@code /user/queue/notifications}；推送在事务提交后执行。
 */
@Service
@RequiredArgsConstructor
public class FriendService {

    private static final Logger log = LoggerFactory.getLogger(FriendService.class);

    public static final String DIRECTION_INCOMING = "INCOMING";
    public static final String DIRECTION_OUTGOING = "OUTGOING";

    private static final String EVENT_FRIEND_REQUEST = "FRIEND_REQUEST";
    private static final String EVENT_FRIEND_ACCEPTED = "FRIEND_ACCEPTED";
    private static final String EVENT_FRIEND_REJECTED = "FRIEND_REJECTED";
    private static final String EVENT_FRIEND_REMOVED = "FRIEND_REMOVED";

    private final FriendRelationMapper friendRelationMapper;
    private final UserMapper userMapper;
    private final UserService userService;
    private final NotificationService notificationService;
    private final RealtimePublisher realtimePublisher;
    private final RealtimeDispatch realtimeDispatch;

    // ------------------------------------------------------------------
    // 申请 / 处理
    // ------------------------------------------------------------------

    /**
     * 发起好友申请。若对方此前已向我发起申请，则互相申请直接成为好友。
     *
     * @throws AppException 404 目标用户不存在；403 存在拉黑关系；409 已是好友/已有待处理申请；422 加自己
     */
    @Transactional
    public FriendRequestResponse sendRequest(String requesterId, String targetUserId) {
        requireNotSelf(requesterId, targetUserId);
        User target = userService.requireActiveById(targetUserId);
        LocalDateTime now = ClockTimes.nowUtc();
        List<FriendRelation> pair = friendRelationMapper.selectPair(requesterId, targetUserId);
        requireNoBlock(requesterId, targetUserId, pair);

        FriendRelation outgoing = findDirected(pair, requesterId, targetUserId).orElse(null);
        FriendRelation incoming = findDirected(pair, targetUserId, requesterId).orElse(null);
        if (isStatus(outgoing, FriendRelation.Status.ACCEPTED) || isStatus(incoming, FriendRelation.Status.ACCEPTED)) {
            throw new AppException(ErrorCode.ALREADY_FRIENDS);
        }
        if (isStatus(outgoing, FriendRelation.Status.PENDING)) {
            throw new AppException(ErrorCode.FRIEND_REQUEST_PENDING);
        }

        User requester = requireUserProfile(requesterId);
        if (isStatus(incoming, FriendRelation.Status.PENDING)) {
            // 互相申请：把对方发来的申请直接同意，并把本方关系补成 ACCEPTED
            updateStatus(incoming, FriendRelation.Status.ACCEPTED, now);
            FriendRelation relation = upsertDirected(requesterId, targetUserId, FriendRelation.Status.ACCEPTED,
                    now, outgoing);
            notifyQuietly(targetUserId, relation, requester, EVENT_FRIEND_ACCEPTED, "好友申请已通过",
                    "对方已通过你的好友申请");
            log.info("互相申请直接成为好友 userId={} targetUserId={}", requesterId, targetUserId);
            return toRequestResponse(relation, DIRECTION_OUTGOING, requester, target);
        }

        // 普通申请；若之前被拒绝（REJECTED）则复用同一行重置为 PENDING，实现"可重新申请"
        FriendRelation relation = upsertDirected(requesterId, targetUserId, FriendRelation.Status.PENDING, now,
                outgoing);
        notifyQuietly(targetUserId, relation, requester, EVENT_FRIEND_REQUEST, "好友申请", "请求添加你为好友");
        log.info("发起好友申请 requesterId={} targetUserId={}", requesterId, targetUserId);
        return toRequestResponse(relation, DIRECTION_OUTGOING, requester, target);
    }

    /**
     * 同意好友申请：仅被申请方可操作；同意后生成双向 ACCEPTED 行。
     */
    @Transactional
    public FriendRequestResponse acceptRequest(String requestId, String userId) {
        FriendRelation request = requirePendingRequest(requestId, userId);
        LocalDateTime now = ClockTimes.nowUtc();
        String requesterId = request.getUserId();
        List<FriendRelation> pair = friendRelationMapper.selectPair(userId, requesterId);
        // 并发/历史数据兜底：存在拉黑关系时不得通过申请（拉黑会清理待处理申请）
        requireNoBlock(userId, requesterId, pair);
        updateStatus(request, FriendRelation.Status.ACCEPTED, now);
        FriendRelation reverse = findDirected(pair, userId, requesterId).orElse(null);
        upsertDirected(userId, requesterId, FriendRelation.Status.ACCEPTED, now, reverse);

        User requester = requireUserProfile(requesterId);
        User self = requireUserProfile(userId);
        // 通知的触发者是"同意方"（当前用户），通知接收者是申请人
        notifyQuietly(requesterId, request, self, EVENT_FRIEND_ACCEPTED, "好友申请已通过",
                "对方已通过你的好友申请");
        log.info("同意好友申请 requestId={} requesterId={} addresseeId={}", requestId, requesterId, userId);
        return toRequestResponse(request, DIRECTION_INCOMING, requester, self);
    }

    /**
     * 拒绝好友申请：仅被申请方可操作；关系置为 REJECTED（保留行以防重复处理，申请方可重新申请）。
     */
    @Transactional
    public FriendRequestResponse rejectRequest(String requestId, String userId) {
        FriendRelation request = requirePendingRequest(requestId, userId);
        LocalDateTime now = ClockTimes.nowUtc();
        updateStatus(request, FriendRelation.Status.REJECTED, now);
        User requester = requireUserProfile(request.getUserId());
        User self = requireUserProfile(userId);
        // 通知的触发者是"拒绝方"（当前用户）
        notifyQuietly(request.getUserId(), request, self, EVENT_FRIEND_REJECTED, "好友申请被拒绝",
                "对方拒绝了你的好友申请");
        log.info("拒绝好友申请 requestId={} requesterId={} addresseeId={}", requestId, request.getUserId(), userId);
        return toRequestResponse(request, DIRECTION_INCOMING, requester, self);
    }

    /**
     * 删除好友：清理双方非拉黑关系（不删除任何一方的拉黑行），幂等。
     */
    @Transactional
    public void removeFriend(String userId, String friendUserId) {
        requireNotSelf(userId, friendUserId);
        List<FriendRelation> pair = friendRelationMapper.selectPair(userId, friendUserId);
        boolean wasFriend = streamHasStatus(pair, FriendRelation.Status.ACCEPTED);
        friendRelationMapper.deleteNonBlockedBetween(userId, friendUserId);
        if (wasFriend) {
            User actor = requireUserProfile(userId);
            pushFriendEvent(friendUserId, null, actor, EVENT_FRIEND_REMOVED, "好友关系已解除", "对方已解除好友关系");
        }
        log.info("删除好友 userId={} friendUserId={} wasFriend={}", userId, friendUserId, wasFriend);
    }

    // ------------------------------------------------------------------
    // 拉黑
    // ------------------------------------------------------------------

    /**
     * 拉黑用户：清理双方申请/好友关系后写入单向 BLOCKED 行；幂等，静默（不通知对方）。
     */
    @Transactional
    public void block(String userId, String targetUserId) {
        requireNotSelf(userId, targetUserId);
        userService.requireActiveById(targetUserId);
        List<FriendRelation> pair = friendRelationMapper.selectPair(userId, targetUserId);
        Optional<FriendRelation> existingBlock = findDirected(pair, userId, targetUserId)
                .filter(relation -> FriendRelation.Status.BLOCKED.name().equals(relation.getStatus()));
        if (existingBlock.isPresent()) {
            return;
        }
        LocalDateTime now = ClockTimes.nowUtc();
        friendRelationMapper.deleteNonBlockedBetween(userId, targetUserId);
        // 清理后重新插入 BLOCKED 行（不能复用刚被删除的关系行做 update，否则会更新 0 行）
        upsertDirected(userId, targetUserId, FriendRelation.Status.BLOCKED, now, null);
        log.info("拉黑用户 userId={} targetUserId={}", userId, targetUserId);
    }

    /**
     * 取消拉黑：仅能删除自己的拉黑行；幂等。
     */
    @Transactional
    public void unblock(String userId, String targetUserId) {
        requireNotSelf(userId, targetUserId);
        int deleted = friendRelationMapper.deleteBlocked(userId, targetUserId);
        log.info("取消拉黑 userId={} targetUserId={} deleted={}", userId, targetUserId, deleted);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * 好友列表：按关系行 ULID 倒序游标分页，批量加载用户资料避免 N+1。
     */
    public PageResult<FriendResponse> listFriends(String userId, String cursor, Integer limit) {
        int pageSize = CursorUtils.normalizeLimit(limit);
        String cursorId = CursorUtils.validateIdCursor(cursor);
        List<FriendRelation> rows = friendRelationMapper.selectByUserAndStatus(userId,
                FriendRelation.Status.ACCEPTED.name(), cursorId, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<FriendRelation> page = hasMore ? rows.subList(0, pageSize) : rows;
        Map<String, User> users = loadUsers(page.stream().map(FriendRelation::getTargetUserId).toList());
        List<FriendResponse> items = page.stream().map(relation -> {
            User friend = users.get(relation.getTargetUserId());
            return new FriendResponse(
                    relation.getTargetUserId(),
                    friend == null ? null : friend.getUsername(),
                    friend == null ? null : friend.getNickname(),
                    friend == null ? null : friend.getAvatarUrl(),
                    friend == null ? null : friend.getSignature(),
                    ClockTimes.toInstant(sinceOf(relation)));
        }).toList();
        String nextCursor = hasMore && !items.isEmpty() ? page.get(page.size() - 1).getId() : null;
        return new PageResult<>(items, nextCursor);
    }

    /**
     * 好友申请列表：{@code direction=incoming}（发给我的）或 {@code outgoing}（我发出的），仅 PENDING。
     */
    public PageResult<FriendRequestResponse> listRequests(String userId, String direction, String cursor,
                                                          Integer limit) {
        String normalized = direction == null || direction.isBlank() ? "incoming" : direction.trim().toLowerCase();
        int pageSize = CursorUtils.normalizeLimit(limit);
        String cursorId = CursorUtils.validateIdCursor(cursor);
        boolean incoming;
        if ("incoming".equals(normalized)) {
            incoming = true;
        } else if ("outgoing".equals(normalized)) {
            incoming = false;
        } else {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "direction 取值必须为 incoming 或 outgoing");
        }
        List<FriendRelation> rows = incoming
                ? friendRelationMapper.selectIncomingPending(userId, cursorId, pageSize + 1)
                : friendRelationMapper.selectByUserAndStatus(userId, FriendRelation.Status.PENDING.name(), cursorId,
                        pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<FriendRelation> page = hasMore ? rows.subList(0, pageSize) : rows;
        List<String> otherIds = page.stream()
                .map(relation -> incoming ? relation.getUserId() : relation.getTargetUserId())
                .toList();
        Map<String, User> users = loadUsers(otherIds);
        User self = requireUserProfile(userId);
        List<FriendRequestResponse> items = page.stream().map(relation -> {
            String otherId = incoming ? relation.getUserId() : relation.getTargetUserId();
            User other = users.get(otherId);
            return incoming
                    ? toRequestResponse(relation, DIRECTION_INCOMING, other, self)
                    : toRequestResponse(relation, DIRECTION_OUTGOING, self, other);
        }).toList();
        String nextCursor = hasMore && !items.isEmpty() ? page.get(page.size() - 1).getId() : null;
        return new PageResult<>(items, nextCursor);
    }

    /**
     * 我拉黑的人列表（用于设置页取消拉黑）。
     */
    public PageResult<BlockedUserResponse> listBlocked(String userId, String cursor, Integer limit) {
        int pageSize = CursorUtils.normalizeLimit(limit);
        String cursorId = CursorUtils.validateIdCursor(cursor);
        List<FriendRelation> rows = friendRelationMapper.selectByUserAndStatus(userId,
                FriendRelation.Status.BLOCKED.name(), cursorId, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<FriendRelation> page = hasMore ? rows.subList(0, pageSize) : rows;
        Map<String, User> users = loadUsers(page.stream().map(FriendRelation::getTargetUserId).toList());
        List<BlockedUserResponse> items = page.stream().map(relation -> {
            User target = users.get(relation.getTargetUserId());
            return new BlockedUserResponse(
                    relation.getTargetUserId(),
                    target == null ? null : target.getUsername(),
                    target == null ? null : target.getNickname(),
                    target == null ? null : target.getAvatarUrl(),
                    ClockTimes.toInstant(relation.getUpdatedAt()));
        }).toList();
        String nextCursor = hasMore && !items.isEmpty() ? page.get(page.size() - 1).getId() : null;
        return new PageResult<>(items, nextCursor);
    }

    /**
     * 两个用户之间的关系状态（用户卡片/按钮展示用）。
     */
    public FriendStatusResponse getStatus(String userId, String targetUserId) {
        requireNotSelf(userId, targetUserId);
        userService.requireActiveById(targetUserId);
        List<FriendRelation> pair = friendRelationMapper.selectPair(userId, targetUserId);
        return new FriendStatusResponse(
                targetUserId,
                streamHasStatus(pair, FriendRelation.Status.ACCEPTED),
                isStatus(findDirected(pair, targetUserId, userId).orElse(null), FriendRelation.Status.PENDING),
                isStatus(findDirected(pair, userId, targetUserId).orElse(null), FriendRelation.Status.PENDING),
                isStatus(findDirected(pair, userId, targetUserId).orElse(null), FriendRelation.Status.BLOCKED),
                isStatus(findDirected(pair, targetUserId, userId).orElse(null), FriendRelation.Status.BLOCKED));
    }

    /**
     * 私信权限辅助：双向拉黑均不可发消息（docs/03 FR-DM-04「被拉黑方不可发送」）。
     *
     * @throws AppException {@link ErrorCode#BLOCKED} 存在任一方向的拉黑关系
     */
    public void assertCanMessage(String userId, String peerId) {
        List<FriendRelation> pair = friendRelationMapper.selectPair(userId, peerId);
        if (pair == null) {
            return;
        }
        for (FriendRelation relation : pair) {
            if (!FriendRelation.Status.BLOCKED.name().equals(relation.getStatus())) {
                continue;
            }
            if (userId.equals(relation.getUserId())) {
                throw new AppException(ErrorCode.BLOCKED, "你已拉黑该用户，无法发送私信");
            }
            if (peerId.equals(relation.getUserId())) {
                throw new AppException(ErrorCode.BLOCKED, "对方已将你拉黑，无法发送私信");
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private FriendRelation requirePendingRequest(String requestId, String userId) {
        FriendRelation request = friendRelationMapper.selectById(requestId);
        if (request == null || !FriendRelation.Status.PENDING.name().equals(request.getStatus())) {
            // 不存在/已处理统一 404，避免泄露他人申请信息
            throw new AppException(ErrorCode.FRIEND_REQUEST_NOT_FOUND);
        }
        if (!userId.equals(request.getTargetUserId())) {
            throw new AppException(ErrorCode.FORBIDDEN, "只能处理发给自己的好友申请");
        }
        if (userId.equals(request.getUserId())) {
            throw new AppException(ErrorCode.FRIEND_REQUEST_NOT_FOUND);
        }
        return request;
    }

    private void requireNoBlock(String userId, String peerId, List<FriendRelation> pair) {
        for (FriendRelation relation : pair) {
            if (!FriendRelation.Status.BLOCKED.name().equals(relation.getStatus())) {
                continue;
            }
            if (userId.equals(relation.getUserId())) {
                throw new AppException(ErrorCode.BLOCKED, "你已拉黑该用户，请先取消拉黑");
            }
            if (peerId.equals(relation.getUserId())) {
                throw new AppException(ErrorCode.BLOCKED, "对方已将你拉黑，无法执行该操作");
            }
        }
    }

    private FriendRelation upsertDirected(String userId, String targetUserId, FriendRelation.Status status,
                                          LocalDateTime now, FriendRelation existing) {
        if (existing != null) {
            existing.setStatus(status.name());
            existing.setUpdatedAt(now);
            friendRelationMapper.updateById(existing);
            return existing;
        }
        FriendRelation relation = new FriendRelation();
        relation.setId(UlidGenerator.generate());
        relation.setUserId(userId);
        relation.setTargetUserId(targetUserId);
        relation.setStatus(status.name());
        relation.setCreatedAt(now);
        relation.setUpdatedAt(now);
        friendRelationMapper.insert(relation);
        return relation;
    }

    private void updateStatus(FriendRelation relation, FriendRelation.Status status, LocalDateTime now) {
        relation.setStatus(status.name());
        relation.setUpdatedAt(now);
        friendRelationMapper.updateById(relation);
    }

    private Optional<FriendRelation> findDirected(List<FriendRelation> pair, String userId, String targetUserId) {
        if (pair == null) {
            return Optional.empty();
        }
        return pair.stream()
                .filter(relation -> userId.equals(relation.getUserId()) && targetUserId.equals(relation.getTargetUserId()))
                .findFirst();
    }

    private boolean isStatus(FriendRelation relation, FriendRelation.Status status) {
        return relation != null && status.name().equals(relation.getStatus());
    }

    /**
     * 判断关系列表中是否存在指定状态的行（按 status 字符串比较，避免对象与字符串误比较）。
     */
    private boolean streamHasStatus(List<FriendRelation> relations, FriendRelation.Status status) {
        if (relations == null) {
            return false;
        }
        return relations.stream().anyMatch(relation -> isStatus(relation, status));
    }

    private void notifyQuietly(String recipientId, FriendRelation relation, User actor, String eventType,
                               String title, String content) {
        notificationService.notifyUser(recipientId, Notification.Type.SYSTEM, null, null, null,
                actor == null ? null : actor.getId(), title, content);
        pushFriendEvent(recipientId, relation, actor, eventType, title, content);
    }

    private void pushFriendEvent(String recipientId, FriendRelation relation, User actor, String eventType,
                                 String title, String content) {
        Instant occurredAt = ClockTimes.toInstant(sinceOf(relation));
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
        NotificationPush push = new NotificationPush(
                relation == null ? null : relation.getId(),
                eventType,
                title,
                content,
                actor == null ? null : actor.getId(),
                actor == null ? null : actor.getNickname(),
                actor == null ? null : actor.getAvatarUrl(),
                relation == null ? null : relation.getId(),
                occurredAt);
        realtimeDispatch.execute(() -> realtimePublisher.publishNotification(recipientId,
                RealtimeEvent.of(eventType, push)));
    }

    private User requireUserProfile(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    private Map<String, User> loadUsers(Collection<String> userIds) {
        List<String> ids = userIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private LocalDateTime sinceOf(FriendRelation relation) {
        if (relation == null) {
            return null;
        }
        return relation.getUpdatedAt() != null ? relation.getUpdatedAt() : relation.getCreatedAt();
    }

    private FriendRequestResponse toRequestResponse(FriendRelation relation, String direction, User fromUser,
                                                    User toUser) {
        return new FriendRequestResponse(
                relation.getId(),
                direction,
                relation.getStatus(),
                toUserBrief(fromUser),
                toUserBrief(toUser),
                ClockTimes.toInstant(relation.getCreatedAt()));
    }

    private FriendUserBrief toUserBrief(User user) {
        if (user == null) {
            return null;
        }
        return new FriendUserBrief(user.getId(), user.getId(), user.getUsername(), user.getNickname(),
                user.getAvatarUrl());
    }

    private void requireNotSelf(String userId, String targetUserId) {
        if (userId == null || targetUserId == null || userId.isBlank() || targetUserId.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "用户 ID 不能为空");
        }
        if (userId.equals(targetUserId)) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "不能对目标用户执行该操作");
        }
    }

    /**
     * 暴露给私信/用户卡片等模块的只读判断：是否好友。不做 404 校验，供展示使用。
     */
    public boolean isFriend(String userId, String targetUserId) {
        if (userId == null || targetUserId == null || userId.equals(targetUserId)) {
            return false;
        }
        List<FriendRelation> pair = friendRelationMapper.selectPair(userId, targetUserId);
        return streamHasStatus(pair, FriendRelation.Status.ACCEPTED);
    }
}
