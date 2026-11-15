package com.isles.realtime.dto;

import java.time.Instant;

/**
 * 个人通知的实时推送负载（{@code /user/queue/notifications}）。
 *
 * <p>好友申请等事件既写入 {@code notifications} 表（REST 列表兜底），也通过本负载实时推送；
 * 前端以 {@code id}（或 {@code type + relatedId}）去重，不依赖推送到达。
 *
 * @param id         推送 ID（好友事件用关系行 ID；通知模块接入后可传真实通知 ID）
 * @param type       通知类型（如 FRIEND_REQUEST/FRIEND_ACCEPTED/FRIEND_REJECTED）
 * @param title      标题（用户可读）
 * @param content    正文（用户可读）
 * @param actorId    触发者用户 ID
 * @param actorNickname 触发者昵称
 * @param actorAvatarUrl 触发者头像
 * @param relatedId  关联资源 ID（好友申请 ID 等），供前端跳转
 * @param createdAt  产生时间（UTC）
 */
public record NotificationPush(
        String id,
        String type,
        String title,
        String content,
        String actorId,
        String actorNickname,
        String actorAvatarUrl,
        String relatedId,
        Instant createdAt) {
}
