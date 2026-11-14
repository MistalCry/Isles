package com.isles.comment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 评论点赞（表 {@code comment_likes}，docs/06 §3.11）。
 */
@Getter
@Setter
@TableName("comment_likes")
public class CommentLike {

    @TableId(type = IdType.INPUT)
    private String id;
    private String commentId;
    private String userId;
    private LocalDateTime createdAt;
}
