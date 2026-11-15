package com.isles.manage.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 编辑身份组：仅更新传入字段（权限点整体替换）。
 */
public record UpdateRoleRequest(
        @Size(min = 1, max = 32, message = "身份组名称长度需为 1–32 个字符") String name,
        @Size(max = 200, message = "描述最长 200 个字符") String description,
        List<String> permissions,
        String scopeBoardId,
        Integer sort) {
}
