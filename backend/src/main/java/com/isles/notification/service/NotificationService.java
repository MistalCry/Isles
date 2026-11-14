package com.isles.notification.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.pagination.CursorUtils;
import com.isles.common.pagination.PageResult;
import com.isles.common.time.ClockTimes;
import com.isles.notification.dto.NotificationResponse;
import com.isles.notification.entity.Notification;
import com.isles.notification.mapper.NotificationMapper;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 通知服务（docs/03 §5.2 NotificationService）：M1 提供互动通知写入、列表与已读。
 *
 * <p>通知写入失败不能影响主流程（发帖/点赞成功优先），这里只做落库；实时推送在 M2 随 WebSocket 上线。
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final UserMapper userMapper;

    /**
     * 写入一条互动通知；自己对自己的操作不通知。
     */
    public void notifyUser(String userId, Notification.Type type, String channelId, String postId,
                           String commentId, String actorId, String title, String content) {
        if (userId == null || userId.equals(actorId)) {
            return;
        }
        Notification notification = new Notification();
        notification.setId(UlidGenerator.generate());
        notification.setUserId(userId);
        notification.setType(type.name());
        notification.setChannelId(channelId);
        notification.setPostId(postId);
        notification.setCommentId(commentId);
        notification.setActorId(actorId);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setIsRead(false);
        notification.setCreatedAt(ClockTimes.nowUtc());
        notificationMapper.insert(notification);
    }

    /**
     * 通知列表：游标分页，支持频道 / 类型 / 未读筛选。
     */
    public PageResult<NotificationResponse> list(String userId, String channelId, String type, boolean unreadOnly,
                                                 String cursor, Integer limit) {
        int pageSize = CursorUtils.normalizeLimit(limit);
        String cursorId = CursorUtils.validateIdCursor(cursor);
        List<Notification> rows = notificationMapper.selectPage(userId, channelId, type, unreadOnly, cursorId,
                pageSize + 1);
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
                    return new NotificationResponse(
                            row.getId(), row.getType(), row.getChannelId(), row.getPostId(), row.getCommentId(),
                            row.getActorId(), actor == null ? null : actor.getNickname(), row.getTitle(), row.getContent(),
                            Boolean.TRUE.equals(row.getIsRead()), ClockTimes.toInstant(row.getCreatedAt()));
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
            notificationMapper.updateById(notification);
        }
        return new NotificationResponse(
                notification.getId(), notification.getType(), notification.getChannelId(), notification.getPostId(),
                notification.getCommentId(), notification.getActorId(), null, notification.getTitle(),
                notification.getContent(), true, ClockTimes.toInstant(notification.getCreatedAt()));
    }

    /**
     * 全部标记已读。
     */
    public int markAllRead(String userId) {
        return notificationMapper.markAllRead(userId);
    }

    /**
     * 查询某用户对指定条件的未读数（M1 未使用，预留给前端红点，后续可缓存）。
     */
    public long countUnread(String userId) {
        Long count = notificationMapper.selectCount(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, false));
        return count == null ? 0 : count;
    }
}
