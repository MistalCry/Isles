package com.isles.post.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 帖子媒体（表 {@code post_media}，docs/06 §3.9）。
 */
@Getter
@Setter
@TableName("post_media")
public class PostMedia {

    @TableId(type = IdType.INPUT)
    private String id;
    private String postId;
    private String mediaType;
    private String url;
    private String thumbUrl;
    private Integer width;
    private Integer height;
    private Long durationMs;
    private Integer sort;
    private LocalDateTime createdAt;

    public enum MediaType {
        IMAGE, VIDEO
    }
}
