package com.isles.manage.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改频道资料（PATCH /channels/{id}）：仅更新传入字段。
 * 修改可见性需要额外 {@code channel.visibility} 权限。
 */
public record UpdateChannelRequest(
        @Size(min = 1, max = 64, message = "频道名长度需为 1–64 个字符") String name,
        @Size(max = 500, message = "头像地址最长 500 个字符") String avatarUrl,
        @Size(max = 500, message = "封面地址最长 500 个字符") String coverUrl,
        @Size(max = 1000, message = "简介最长 1000 个字符") String description,
        @Pattern(regexp = "PUBLIC|PRIVATE", message = "频道类型取值非法") String type,
        @Pattern(regexp = "PUBLIC_VISIBLE|MEMBERS_ONLY", message = "可见性取值非法") String visibility) {
}
