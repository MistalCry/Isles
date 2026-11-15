package com.isles.dm.dto;

import com.isles.common.dto.MediaItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 发送私信请求（{@code POST /api/v1/dm/conversations/{id}/messages}）。
 *
 * <p>按 {@code type} 使用不同字段（服务端二次校验，前端校验仅为体验）：
 * <ul>
 *   <li>{@code TEXT}：{@code content} 必填（≤ 4000 字符）；</li>
 *   <li>{@code IMAGE}：{@code media} 取第一张图片（前端先走 {@code /api/v1/media/upload}），
 *       兼容只传 {@code mediaUrl}/{@code thumbUrl} 的调用；{@code content} 作图片说明；</li>
 *   <li>{@code POST}：{@code postId} 必填，服务端构建分享卡片（postId/标题/频道/封面）。</li>
 * </ul>
 *
 * @param type            消息类型 TEXT/IMAGE/POST
 * @param content         文本内容或图片说明
 * @param media           图片媒体（IMAGE，取第一项）
 * @param mediaUrl        图片地址（IMAGE，兼容无 media 数组的调用）
 * @param thumbUrl        图片缩略图（IMAGE，可选）
 * @param postId          被分享的帖子 ID（POST）
 * @param clientMessageId 客户端消息 ID（可选，用于发送重试去重）
 */
public record SendMessageRequest(
        @NotBlank(message = "type 不能为空")
        @Size(max = 16, message = "type 长度非法")
        String type,

        @Size(max = 4000, message = "消息内容最长 4000 个字符")
        String content,

        List<MediaItem> media,

        @Size(max = 500, message = "mediaUrl 长度非法")
        String mediaUrl,

        @Size(max = 500, message = "thumbUrl 长度非法")
        String thumbUrl,

        @Size(max = 26, message = "postId 长度非法")
        String postId,

        @Size(max = 64, message = "clientMessageId 最长 64 个字符")
        String clientMessageId) {
}
