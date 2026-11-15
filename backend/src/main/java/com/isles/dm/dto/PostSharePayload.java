package com.isles.dm.dto;

/**
 * 帖子分享卡片（{@code dm_messages.payload} JSON，type=POST；docs/03 FR-DM-05）。
 *
 * <p>接收方据 {@code postId} 跳转帖子详情；{@code title/channelName/coverUrl} 用于在会话列表与气泡中
 * 直接渲染卡片，避免前端再请求帖子接口。
 *
 * @param postId      帖子 ID
 * @param title       展示标题（无标题时取正文纯文本摘要）
 * @param channelId   所属频道 ID（跳转上下文用）
 * @param channelName 频道名
 * @param coverUrl    封面图（可空）
 */
public record PostSharePayload(
        String postId,
        String title,
        String channelId,
        String channelName,
        String coverUrl) {
}
