package com.isles.post.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 帖子点赞（表 {@code post_likes}，docs/06 §3.11）：唯一约束保证防重复。
 */
@Getter
@Setter
@TableName("post_likes")
public class PostLike {

    @TableId(type = IdType.INPUT)
    private String id;
    private String postId;
    private String userId;
    private LocalDateTime createdAt;
}
