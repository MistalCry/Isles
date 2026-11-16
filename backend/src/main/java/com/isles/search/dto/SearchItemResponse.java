package com.isles.search.dto;

import com.isles.common.dto.MediaItem;

import java.time.Instant;
import java.util.List;

/**
 * 搜索结果条目：频道与帖子共用，以 {@code type} 区分（CHANNEL / POST）。
 *
 * <p>POST 结果额外携带作者、板块与首图，便于前端复用帖子卡片；CHANNEL 结果的
 * {@code title/description} 即频道名/简介，{@code memberCount} 为成员数。
 *
 * <p>媒体：{@code coverUrl} 为帖子显式封面；{@code media} 只含**首图**（post_media 中 sort 最小的一张），
 * 避免搜索结果因媒体列表膨胀；两者都为空时前端走纯文字卡片。
 *
 * <p>AI 标识（docs/12 §6）：{@code isAiGenerated} 帖子由 AI 代理生成，{@code authorIsAgent} 作者是代理账号。
 * 搜索结果也是帖子卡片，合规标识必须一致，所以这两个字段与 {@code PostResponse} 同名同义。
 */
public record SearchItemResponse(
        String type,
        String id,
        String title,
        String summary,
        String channelId,
        String channelName,
        String channelAvatarUrl,
        String avatarUrl,
        Long memberCount,
        Long likeCount,
        Long commentCount,
        String authorId,
        String authorUsername,
        String authorNickname,
        String authorAvatarUrl,
        String boardId,
        String boardName,
        Instant createdAt,
        String coverUrl,
        List<MediaItem> media,
        boolean isAiGenerated,
        boolean authorIsAgent) {

    public SearchItemResponse {
        media = media == null ? List.of() : List.copyOf(media);
    }
}
