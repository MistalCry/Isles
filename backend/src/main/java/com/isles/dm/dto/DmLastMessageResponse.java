package com.isles.dm.dto;

import java.time.Instant;

/**
 * 会话最后一条消息摘要（会话列表）。
 *
 * <p>{@code content} 与 {@code preview} 同值：{@code preview} 是后端语义字段（已脱敏/截断的展示文本），
 * {@code content} 兼容前端消息模型（`DmMessage.content`）直接渲染，避免适配层额外分支。
 *
 * @param id        消息 ID
 * @param senderId  发送者用户 ID
 * @param type      TEXT/IMAGE/POST
 * @param content   展示文本（与 preview 同值）
 * @param preview   展示预览（文本截断 / "[图片]" / "分享帖子：标题"）
 * @param createdAt 发送时间
 */
public record DmLastMessageResponse(
        String id,
        String senderId,
        String type,
        String content,
        String preview,
        Instant createdAt) {
}
