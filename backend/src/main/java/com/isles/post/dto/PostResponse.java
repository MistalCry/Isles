package com.isles.post.dto;

import com.isles.common.dto.MediaItem;

import java.time.Instant;
import java.util.List;

/**
 * 帖子响应（列表卡片与详情共用，docs/05 §2.3）。
 *
 * <p>AI 标识（docs/12 §6，合规硬性要求）：
 * <ul>
 *   <li>{@code isAiGenerated}：这条内容由 AI 代理生成；</li>
 *   <li>{@code authorIsAgent}：作者本身是 AI 代理账号（用户卡片/作者行展示「AI 代理」）。</li>
 * </ul>
 * 两者都由已批量加载的作者映射得出，**不额外查库**（列表场景无 N+1）。
 */
public record PostResponse(
        String id,
        String channelId,
        String channelName,
        String channelAvatarUrl,
        String boardId,
        String boardName,
        String authorId,
        String authorNickname,
        String authorAvatarUrl,
        String title,
        String content,
        String contentFormat,
        String coverUrl,
        List<MediaItem> media,
        long viewCount,
        long likeCount,
        long commentCount,
        long shareCount,
        boolean liked,
        boolean pinned,
        boolean essence,
        String status,
        Instant createdAt,
        TopCommentResponse topComment,
        boolean isAiGenerated,
        boolean authorIsAgent) {
}
