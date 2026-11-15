package com.isles.social.dto;

import jakarta.validation.constraints.Size;

/**
 * 好友相关操作的目标用户请求体：{@code POST /friends/requests}（发起申请）、
 * {@code POST /users/{userId}/block} 等。
 *
 * <p>字段兼容：前端契约使用 {@code userId}，后端其它接口使用语义更明确的 {@code targetUserId}；
 * 二者至少传一个，服务端统一解析。{@code message} 为申请附言（M2 暂不落库，仅校验长度，预留展示位）。
 *
 * @param userId       目标用户 ID（前端契约字段）
 * @param targetUserId 目标用户 ID（语义别名）
 * @param message      申请附言（可选，当前版本不落库）
 */
public record FriendTargetRequest(
        @Size(max = 26, message = "userId 长度非法")
        String userId,

        @Size(max = 26, message = "targetUserId 长度非法")
        String targetUserId,

        @Size(max = 200, message = "附言最长 200 个字符")
        String message) {

    /**
     * 解析目标用户 ID：优先 {@code targetUserId}，其次 {@code userId}。
     */
    public String resolveTargetUserId() {
        if (targetUserId != null && !targetUserId.isBlank()) {
            return targetUserId.trim();
        }
        return userId == null ? null : userId.trim();
    }
}
