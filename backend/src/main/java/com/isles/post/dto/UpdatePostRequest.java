package com.isles.post.dto;

import com.isles.common.dto.MediaItem;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 编辑帖子（docs/03 FR-POST-07）：仅作者本人可编辑，字段可选，未传的字段保持不变。
 *
 * <p>不提供板块迁移：板块归属影响频道内的分类与权限范围，改动应通过删除后重发完成。
 */
public record UpdatePostRequest(
        @Size(max = 200, message = "标题最长 200 个字符")
        String title,

        @Size(max = 100_000, message = "正文过长")
        String content,

        @Pattern(regexp = "PLAIN|HTML", message = "内容格式取值非法")
        String contentFormat,

        @Size(max = 500, message = "封面地址最长 500 个字符")
        String coverUrl,

        @Size(max = 9, message = "最多 9 个媒体文件")
        List<MediaItem> media) {
}
