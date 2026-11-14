package com.isles.post.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 帖子（表 {@code posts}，docs/06 §3.8）。
 */
@Getter
@Setter
@TableName("posts")
public class Post {

    @TableId(type = IdType.INPUT)
    private String id;
    private String channelId;
    private String boardId;
    private String authorId;
    private String title;
    private String content;
    private String contentFormat;
    private String coverUrl;
    private Long viewCount;
    private Long likeCount;
    private Long commentCount;
    private Long shareCount;
    private Boolean isPinned;
    private Boolean isEssence;
    private LocalDateTime pinnedAt;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public enum ContentFormat {
        PLAIN, HTML
    }

    public enum Status {
        PUBLISHED, DELETED, HIDDEN
    }
}
