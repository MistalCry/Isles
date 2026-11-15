package com.isles.dm.dto;

import com.isles.common.dto.MediaItem;

import java.time.Instant;
import java.util.List;

/**
 * 私信消息响应（发送接口返回 / 历史消息列表项）。
 *
 * @param id               消息 ID
 * @param conversationId   会话 ID
 * @param senderId         发送者用户 ID
 * @param senderNickname   发送者昵称
 * @param senderAvatarUrl  发送者头像
 * @param receiverId       接收者用户 ID
 * @param type             TEXT/IMAGE/POST
 * @param content          文本内容 / 图片说明
 * @param media            图片媒体（type=IMAGE 时返回，复用帖子媒体结构）
 * @param post             帖子分享卡片（type=POST）
 * @param read             接收方是否已读
 * @param readAt           已读时间
 * @param clientMessageId  客户端消息 ID（发送方用于对账/去重）
 * @param createdAt        发送时间
 */
public record DmMessageResponse(
        String id,
        String conversationId,
        String senderId,
        String senderNickname,
        String senderAvatarUrl,
        String receiverId,
        String type,
        String content,
        List<MediaItem> media,
        PostSharePayload post,
        boolean read,
        Instant readAt,
        String clientMessageId,
        Instant createdAt) {
}
