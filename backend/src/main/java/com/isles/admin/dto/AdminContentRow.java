package com.isles.admin.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 平台内容治理列表行（帖子/评论共用）。
 *
 * <p>正文只取前 200 字符做预览，避免把 MEDIUMTEXT 全量读进内存。
 */
@Getter
@Setter
public class AdminContentRow {

    private String id;
    private String targetType;
    private String channelId;
    private String authorId;
    private String title;
    private String contentPreview;
    private String status;
    private LocalDateTime createdAt;
}
