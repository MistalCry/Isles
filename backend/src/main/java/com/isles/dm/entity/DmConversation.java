package com.isles.dm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 私信会话（表 {@code dm_conversations}，V4__social_realtime.sql）。
 *
 * <p>不变量：{@code userLowId < userHighId}（ULID 字典序），一对用户只有一条会话；
 * 未读计数按 low/high 两侧冗余存储，进入会话时按当前用户侧清零。
 */
@Getter
@Setter
@TableName("dm_conversations")
public class DmConversation {

    @TableId(type = IdType.INPUT)
    private String id;
    private String userLowId;
    private String userHighId;
    private String lastMessageId;
    private String lastMessageSenderId;
    private String lastMessageType;
    private String lastMessagePreview;
    private LocalDateTime lastMessageAt;
    private Long unreadLow;
    private Long unreadHigh;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
