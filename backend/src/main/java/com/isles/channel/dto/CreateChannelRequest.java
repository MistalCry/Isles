package com.isles.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建频道请求（docs/05 §2.2 / §3.2）。
 */
public record CreateChannelRequest(
        @NotBlank(message = "频道名不能为空")
        @Size(max = 64, message = "频道名最长 64 个字符")
        String name,

        @NotBlank(message = "频道头像不能为空")
        @Size(max = 500, message = "头像地址最长 500 个字符")
        String avatarUrl,

        @Size(max = 500, message = "封面地址最长 500 个字符")
        String coverUrl,

        @Size(max = 1000, message = "频道简介最长 1000 个字符")
        String description,

        @Pattern(regexp = "PUBLIC|PRIVATE", message = "频道类型取值非法")
        String type,

        @Pattern(regexp = "PUBLIC_VISIBLE|MEMBERS_ONLY", message = "可见性取值非法")
        String visibility) {
}
