package com.isles.comment.dto;

import com.isles.common.dto.MediaItem;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 发表评论/回复请求（docs/05 §2.3）：正文、媒体、表情至少一项非空。
 */
public record CreateCommentRequest(
        @Size(max = 2000, message = "评论最长 2000 个字符") String content,

        String parentId,

        String replyToUserId,

        @Size(max = 9, message = "最多 9 个媒体文件") List<MediaItem> media,

        @Size(max = 20, message = "表情最多 20 个") List<String> emoji) {
}
