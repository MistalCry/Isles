package com.isles.notification.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.json.JsonSupport;
import com.isles.common.pagination.CursorUtils;
import com.isles.common.pagination.PageResult;
import com.isles.common.time.ClockTimes;
import com.isles.notification.dto.NotificationResponse;
import com.isles.notification.dto.UnreadCountResponse;
import com.isles.notification.entity.Notification;
import com.isles.realtime.RealtimeDispatch;
import com.isles.realtime.RealtimePublisher;
import com.isles.realtime.dto.RealtimeEvent;
import com.isles.notification.mapper.NotificationMapper;
import com.isles.notification.mapper.row.UnreadCategoryRow;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 通知服务（docs/03 §5.2 NotificationService）：互动/系统消息分类、按频道过滤、未读数、已读。
 *
 * <p>落库后经 {@link RealtimeDispatch} 在事务提交后推送 {@code /user/queue/notifications}；
 * 推送是尽力而为通道，客户端断线重连后按 REST 游标补拉（docs/02 §5.4）。
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    /** 群发单批用户数：500 行一条 INSERT，兼顾往返次数与 SQL 长度。 */
    private static final int BROADCAST_BATCH_SIZE = 500;

    private final NotificationMapper notificationMapper;
    private final UserMapper userMapper;
    private final JsonSupport jsonSupport;
    private final RealtimePublisher realtimePublisher;
    private final RealtimeDispatch realtimeDispatch;

    /**
     * 写入互动通知（旧签名，兼容 M1 调用方）：分类按类型自动判定。
     */
    public void notifyUser(String userId, Notification.Type type, String channelId, String postId,
                           String commentId, String actorId, String title, String content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "channelId", channelId);
        putIfPresent(payload, "postId", postId);
        putIfPresent(payload, "commentId", commentId);
        doNotify(userId, type, categoryOf(type), channelId, postId, commentId, actorId, title, content, payload);
    }

    /**
     * 写入系统通知（举报结果、审批结果、内容处置等）。
     */
    public void notifySystem(String userId, Notification.Type type, String channelId, String postId,
                             String commentId, String title, String content, Map<String, Object> payload) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (payload != null) {
            merged.putAll(payload);
        }
        putIfPresent(merged, "channelId", channelId);
        putIfPresent(merged, "postId", postId);
        putIfPresent(merged, "commentId", commentId);
        doNotify(userId, type, Notification.Category.SYSTEM.name(), channelId, postId, commentId, null,
                title, content, merged);
    }

    /**
     * 站内信群发：给全站活跃用户各写一条系统通知（公告发布等场景）。
     *
     * <p>实现要点（docs/11 §5.5）：
     * <ul>
     *   <li>按 id 游标分批取用户（每批 {@link #BROADCAST_BATCH_SIZE}），避免 OFFSET 深分页；</li>
     *   <li>每批一条多行 INSERT，十万用户也只有约 200 次往返，不会一次拼出超长 SQL；</li>
     *   <li>不做逐用户 WebSocket 推送（十万条实时事件会打爆推送通道），客户端下次拉取列表即可见。</li>
     * </ul>
     *
     * @return 实际写入的通知条数
     */
    public int broadcastSystem(Notification.Type type, String title, String content, Map<String, Object> payload) {
        String payloadJson = payload == null || payload.isEmpty() ? null : jsonSupport.toJson(payload);
        String afterId = null;
        int total = 0;
        while (true) {
            List<String> userIds = userMapper.selectActiveIdsAfter(afterId, BROADCAST_BATCH_SIZE);
            if (userIds.isEmpty()) {
                break;
            }
            LocalDateTime now = ClockTimes.nowUtc();
            List<Notification> batch = new java.util.ArrayList<>(userIds.size());
            for (String userId : userIds) {
                Notification notification = new Notification();
                notification.setId(UlidGenerator.generate());
                notification.setUserId(userId);
                notification.setType(type.name());
                notification.setCategory(Notification.Category.SYSTEM.name());
                notification.setTitle(title);
                notification.setContent(content);
                notification.setPayload(payloadJson);
                notification.setIsRead(false);
                notification.setCreatedAt(now);
                batch.add(notification);
            }
            notificationMapper.insertBatch(batch);
            total += batch.size();
            afterId = userIds.get(userIds.size() - 1);
            if (userIds.size() < BROADCAST_BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    private void doNotify(String userId, Notification.Type type, String category, String channelId, String postId,
                          String commentId, String actorId, String title, String content,
                          Map<String, Object> payload) {
        if (userId == null) {
            return;
        }
        if (userId.equals(actorId) && actorId != null) {
            return;
        }
        LocalDateTime now = ClockTimes.nowUtc();
        Notification notification = new Notification();
        notification.setId(UlidGenerator.generate());
        notification.setUserId(userId);
        notification.setType(type.name());
        notification.setCategory(category);
        notification.setChannelId(channelId);
        notification.setPostId(postId);
        notification.setCommentId(commentId);
        notification.setActorId(actorId);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setPayload(payload == null || payload.isEmpty() ? null : jsonSupport.toJson(payload));
        notification.setIsRead(false);
        notification.setCreatedAt(now);
        notificationMapper.insert(notification);
        pushRealtime(notification);
    }

    /**
     * 事务提交后推送通知事件（docs/03 §9「通知推送 p95 ≤ 500ms」）。
     *
     * <p>事件信封 {@code {id,type,ts,version,data}}，{@code data} 为通知条目本身，
     * 前端 {@code stores/notifications} 直接按 data 渲染并累加未读。
     */
    private void pushRealtime(Notification notification) {
        User actor = notification.getActorId() == null ? null : userMapper.selectById(notification.getActorId());
        RealtimeEvent event = RealtimeEvent.of("NOTIFICATION", toResponse(notification, actor, null));
        realtimeDispatch.execute(() -> realtimePublisher.publishNotification(notification.getUserId(), event));
    }

    /**
     * 通知列表：游标分页，支持频道 / 类型 / 分类 / 未读筛选。
     */
    public PageResult<NotificationResponse> list(String userId, String channelId, String type, String category,
                                                 boolean unreadOnly, String cursor, Integer limit) {
        int pageSize = CursorUtils.normalizeLimit(limit);
        String cursorId = CursorUtils.validateIdCursor(cursor);
        List<Notification> rows = notificationMapper.selectPage(userId, channelId, type, category, unreadOnly,
                cursorId, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<Notification> page = hasMore ? rows.subList(0, pageSize) : rows;

        List<String> actorIds = page.stream()
                .map(Notification::getActorId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<String, User> actors = actorIds.isEmpty()
                ? Map.of()
                : userMapper.selectBatchIds(actorIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));

        List<NotificationResponse> items = page.stream()
                .map(row -> {
                    User actor = row.getActorId() == null ? null : actors.get(row.getActorId());
                    return toResponse(row, actor, null);
                })
                .toList();
        String nextCursor = hasMore && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
        return new PageResult<>(items, nextCursor);
    }

    /**
     * 标记单条已读：只能操作自己的通知（防 IDOR）。
     */
    public NotificationResponse markRead(String notificationId, String userId) {
        Notification notification = notificationMapper.selectById(notificationId);
        if (notification == null || !userId.equals(notification.getUserId())) {
            throw new AppException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
        if (!Boolean.TRUE.equals(notification.getIsRead())) {
            notification.setIsRead(true);
            notification.setReadAt(ClockTimes.nowUtc());
            notificationMapper.updateById(notification);
        }
        User actor = notification.getActorId() == null ? null : userMapper.selectById(notification.getActorId());
        return toResponse(notification, actor, null);
    }

    /**
     * 全部标记已读（可按频道 / 分类限定）。
     */
    public int markAllRead(String userId, String channelId, String category) {
        return notificationMapper.markAllRead(userId, channelId, category);
    }

    /**
     * 未读数：总数 + 按分类。
     */
    public UnreadCountResponse unreadCount(String userId) {
        List<UnreadCategoryRow> rows = notificationMapper.selectUnreadByCategory(userId);
        Map<String, Long> byCategory = new HashMap<>();
        byCategory.put(Notification.Category.INTERACTION.name(), 0L);
        byCategory.put(Notification.Category.SYSTEM.name(), 0L);
        long total = 0;
        for (UnreadCategoryRow row : rows) {
            long count = row.getUnreadCount() == null ? 0 : row.getUnreadCount();
            byCategory.put(row.getCategory(), count);
            total += count;
        }
        return new UnreadCountResponse(total, byCategory);
    }

    /**
     * 未读总数（兼容旧调用）。
     */
    public long countUnread(String userId) {
        Long count = notificationMapper.selectCount(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, false));
        return count == null ? 0 : count;
    }

    private NotificationResponse toResponse(Notification row, User actor, Map<String, Object> ignored) {
        return new NotificationResponse(
                row.getId(),
                row.getType(),
                row.getCategory(),
                row.getChannelId(),
                row.getPostId(),
                row.getCommentId(),
                row.getActorId(),
                actor == null ? null : actor.getNickname(),
                row.getTitle(),
                row.getContent(),
                parsePayload(row.getPayload()),
                Boolean.TRUE.equals(row.getIsRead()),
                ClockTimes.toInstant(row.getReadAt()),
                ClockTimes.toInstant(row.getCreatedAt()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            return jsonSupport.fromJson(payload, Map.class);
        } catch (RuntimeException ex) {
            // 历史数据/异常 payload 不影响列表可用性
            return Map.of();
        }
    }

    private String categoryOf(Notification.Type type) {
        return type == Notification.Type.SYSTEM
                ? Notification.Category.SYSTEM.name()
                : Notification.Category.INTERACTION.name();
    }

    private void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
