package com.isles.dm.dto;

/**
 * 会话对方用户摘要（会话列表/消息气泡渲染用）。
 *
 * <p>{@code id} 与 {@code userId} 同时返回：前端 `UserSummary` 统一使用 {@code id}，
 * 而后端其它接口惯用 {@code userId}，两者兼容避免适配层多写分支。
 */
public record DmPeerResponse(
        String id,
        String userId,
        String username,
        String nickname,
        String avatarUrl) {
}
