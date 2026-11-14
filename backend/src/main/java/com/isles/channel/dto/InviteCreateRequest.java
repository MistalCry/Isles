package com.isles.channel.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 创建邀请链接请求（POST /channels/{id}/invites，FR-CH-15）。
 */
public record InviteCreateRequest(
        Boolean requiresApproval,

        @Min(value = 60, message = "有效期最短 60 秒")
        Long expiresInSeconds,

        @Min(value = 1, message = "最大使用次数至少为 1")
        Integer maxUses) {
}
