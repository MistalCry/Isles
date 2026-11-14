package com.isles.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改个人资料（PATCH /users/me）：仅允许头像/昵称/签名/性别/地区，用户名不可改（FR-USER-01）。
 */
public record UpdateProfileRequest(
        @Size(max = 500, message = "头像地址最长 500 个字符") String avatarUrl,
        @Size(min = 1, max = 32, message = "昵称长度需为 1–32 个字符") String nickname,
        @Size(max = 200, message = "签名最长 200 个字符") String signature,
        @Pattern(regexp = "MALE|FEMALE|UNKNOWN", message = "性别取值非法") String gender,
        @Size(max = 64, message = "地区最长 64 个字符") String region) {
}
