package com.isles.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求（docs/05 §2.1、FR-AUTH-01）：用户名 3–20 字符且唯一；密码 8–64 位且包含字母与数字。
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_]{3,20}$", message = "用户名需为 3–20 位字母、数字或下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$", message = "密码需为 8–64 位且同时包含字母与数字")
        String password,

        @Size(max = 32, message = "昵称最长 32 个字符")
        String nickname) {
}
