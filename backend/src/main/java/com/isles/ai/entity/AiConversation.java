package com.isles.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * AI 会话（表 {@code ai_conversations}，docs/03 FR-AIQA-06）。
 *
 * <p>scope=GLOBAL 为「频道问问」（检索全站公开帖子）；scope=CHANNEL 为「频道助手」（仅本频道内容）。
 */
@Getter
@Setter
@TableName("ai_conversations")
public class AiConversation {

    @TableId(type = IdType.INPUT)
    private String id;
    private String userId;
    private String scope;
    private String channelId;
    private String title;
    private String modelConfigId;
    private Integer messageCount;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public enum Scope {
        GLOBAL, CHANNEL
    }
}
