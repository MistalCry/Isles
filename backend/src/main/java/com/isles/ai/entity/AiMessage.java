package com.isles.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * AI 消息（表 {@code ai_messages}，docs/03 FR-AIQA-09）。
 *
 * <p>{@code reasoningContent} 保存深度思考过程；{@code sources} 为引用帖子的 JSON 数组；
 * {@code images} 为用户上传图片 URL 的 JSON 数组。
 */
@Getter
@Setter
@TableName("ai_messages")
public class AiMessage {

    @TableId(type = IdType.INPUT)
    private String id;
    private String conversationId;
    private String userId;
    private String role;
    private String content;
    private String reasoningContent;
    private String images;
    private String sources;
    private String model;
    private String modelConfigId;
    private Integer tokenIn;
    private Integer tokenOut;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum Role {
        USER, ASSISTANT, SYSTEM
    }

    public enum Status {
        COMPLETED, STREAMING, ABORTED, FAILED
    }
}
