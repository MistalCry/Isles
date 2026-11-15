package com.isles.ai.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 检索上下文帖子（跨表查询结果，仅用于提示词拼装，不对外返回完整正文）。
 */
@Getter
@Setter
public class AiContextPost {

    private String id;
    private String channelId;
    private String channelName;
    private String authorId;
    private String title;
    private String content;
    private Long viewCount;
    private Long likeCount;
    private Long commentCount;
    private LocalDateTime createdAt;
}
