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
    /** INTERACTION（互动消息）/ SYSTEM（系统消息），V2 新增。 */
    private String category;
    private String channelId;
    private String postId;
    private String commentId;
    private String actorId;
    private String title;
    private String content;
    /** 跳转与展示附加数据 JSON（V2 新增）。 */
    private String payload;
    private Boolean isRead;
    /** 已读时间（V2 新增）。 */
    private LocalDateTime readAt;
    private LocalDateTime createdAt;

    public enum Type {
        POST_LIKED, POST_COMMENTED, COMMENT_REPLIED, SYSTEM,
        REPORT_RESULT, JOIN_APPROVED, JOIN_REJECTED, MEMBER_MUTED, MEMBER_REMOVED, POST_REMOVED, ROLE_CHANGED
    }

    public enum Category {
        INTERACTION, SYSTEM
    }
}
