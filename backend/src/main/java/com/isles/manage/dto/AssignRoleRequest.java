package com.isles.manage.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 给成员分配身份组。
 */
public record AssignRoleRequest(@NotBlank(message = "身份组不能为空") String roleId) {
}
