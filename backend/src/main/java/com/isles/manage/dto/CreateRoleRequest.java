package com.isles.manage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新建自定义身份组（FR-ROLE-02）。
 */
public record CreateRoleRequest(
        @NotBlank(message = "身份组名称不能为空")
        @Size(max = 32, message = "身份组名称最长 32 个字符")
        String name,
        @Size(max = 200, message = "描述最长 200 个字符") String description,
        List<String> permissions,
        String scopeBoardId,
        Integer sort) {
}
