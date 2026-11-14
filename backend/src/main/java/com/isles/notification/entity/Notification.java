package com.isles.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 通知（表 {@code notifications}，docs/06 §3.11）：M1 提供基础列表与已读。
 */
@Getter
@Setter
@TableName("notifications")
public class Notification {

    @TableId(type = IdType.INPUT)
    private String id;
    private String userId;
    private String type;
    private String channelId;
    private String postId;
    private String commentId;
    private String actorId;
    private String title;
    private String content;
    private Boolean isRead;
    private LocalDateTime createdAt;

    public enum Type {
        POST_LIKED, POST_COMMENTED, COMMENT_REPLIED, SYSTEM
    }
}
