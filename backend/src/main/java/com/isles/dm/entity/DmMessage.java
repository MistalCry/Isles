package com.isles.dm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 私信消息（表 {@code dm_messages}，V4__social_realtime.sql）。
 *
 * <p>{@code id} 为 ULID，字典序即发送时间序，历史分页直接以最后一条消息 ID 作游标。
 * {@code payload} 为 JSON 字符串：IMAGE 存媒体地址，POST 存帖子分享卡片摘要。
 */
@Getter
@Setter
@TableName("dm_messages")
public class DmMessage {

    @TableId(type = IdType.INPUT)
    private String id;
    private String conversationId;
    private String senderId;
    private String receiverId;
    private String type;
    private String content;
    private String payload;
    private String clientMessageId;
    private Boolean isRead;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;

    /** 消息类型（docs/03 FR-DM-02/05）：文本、图片、帖子分享。 */
    public enum Type {
        TEXT, IMAGE, POST
    }
}
