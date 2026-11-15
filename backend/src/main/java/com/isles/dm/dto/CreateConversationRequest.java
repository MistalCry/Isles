package com.isles.dm.dto;

import jakarta.validation.constraints.Size;

/**
 * 创建/打开一对一会话请求（{@code POST /api/v1/dm/conversations}）。
 *
 * <p>任何用户都可向任意用户发起私信（docs/03 §11 决策 5），无需好友关系；
 * 被拉黑或拉黑对方时发送消息会被拒绝（会话本身可查看历史）。
 *
 * <p>字段兼容：前端契约使用 {@code userId}，其它调用方可使用语义更明确的 {@code peerId}；
 * 二者至少传一个，服务端在 {@code DmService} 中统一解析（见 {@code resolvePeerId()}）。
 *
 * @param userId 对方用户 ID（前端契约字段）
 * @param peerId 对方用户 ID（语义别名）
 */
public record CreateConversationRequest(
        @Size(max = 26, message = "userId 长度非法")
        String userId,

        @Size(max = 26, message = "peerId 长度非法")
        String peerId) {

    /**
     * 解析对方用户 ID：优先 {@code peerId}，其次 {@code userId}。
     */
    public String resolvePeerId() {
        if (peerId != null && !peerId.isBlank()) {
            return peerId.trim();
        }
        return userId == null ? null : userId.trim();
    }
}
