package com.isles.comment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 评论（表 {@code comments}，docs/06 §3.10）：顶级评论 parentId 为空，回复指向顶级评论。
 */
@Getter
@Setter
@TableName("comments")
public class Comment {

    @TableId(type = IdType.INPUT)
    private String id;
    private String postId;
    private String authorId;
    private String parentId;
    private String replyToUserId;
    private String content;
    /** JSON 数组字符串：[{type,url,thumbUrl}]。 */
    private String media;
    /** JSON 数组字符串：表情列表。 */
    private String emoji;
    private Long likeCount;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;

    public enum Status {
        PUBLISHED, DELETED, HIDDEN
    }
}
