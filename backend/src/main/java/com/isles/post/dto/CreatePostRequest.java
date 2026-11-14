package com.isles.post.dto;

import com.isles.common.dto.MediaItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 发帖请求（docs/05 §2.3 / §3.3）：快捷发帖与长文共用，板块必填。
 */
public record CreatePostRequest(
        @NotBlank(message = "板块不能为空")
        String boardId,

        @Size(max = 200, message = "标题最长 200 个字符")
        String title,

        @NotBlank(message = "正文不能为空")
        @Size(max = 100_000, message = "正文过长")
        String content,

        @Pattern(regexp = "PLAIN|HTML", message = "内容格式取值非法")
        String contentFormat,

        @Size(max = 500, message = "封面地址最长 500 个字符")
        String coverUrl,

        @Size(max = 9, message = "最多 9 个媒体文件")
        List<MediaItem> media) {
}
