package com.isles.dm.dto;

import java.time.Instant;

/**
 * 私信会话响应（{@code GET /api/v1/dm/conversations}、会话创建/详情返回）。
 *
 * @param id            会话 ID
 * @param peer          对方用户摘要
 * @param lastMessage   最后一条消息摘要（无消息时为 null）
 * @param unreadCount   当前用户侧未读数
 * @param lastMessageAt 会话活跃时间（排序键）
 * @param createdAt     会话创建时间
 */
public record ConversationResponse(
        String id,
        DmPeerResponse peer,
        DmLastMessageResponse lastMessage,
        long unreadCount,
        Instant lastMessageAt,
        Instant createdAt) {
}
