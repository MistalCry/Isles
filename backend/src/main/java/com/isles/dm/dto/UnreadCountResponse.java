package com.isles.dm.dto;

/**
 * 全站未读私信总数（{@code GET /api/v1/dm/unread-count}），用于侧边栏"我的私信"红点。
 *
 * @param total 所有会话未读之和
 */
public record UnreadCountResponse(long total) {
}
