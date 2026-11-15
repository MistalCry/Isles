package com.isles.dm.dto;

/**
 * 标记会话已读结果（{@code POST /api/v1/dm/conversations/{id}/read}）。
 *
 * @param conversationId 会话 ID
 * @param readCount      本次标记已读的消息数
 * @param unreadCount    标记后当前用户侧未读数（恒为 0，便于前端直接更新红点）
 */
public record MarkReadResponse(
        String conversationId,
        int readCount,
        long unreadCount) {
}
