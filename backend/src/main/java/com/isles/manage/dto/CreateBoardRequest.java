package com.isles.manage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建板块（FR-BOARD-02）。
 */
public record CreateBoardRequest(
        @NotBlank(message = "板块名不能为空")
        @Size(max = 32, message = "板块名最长 32 个字符")
        String name,
        @Size(max = 200, message = "板块描述最长 200 个字符") String description,
        Integer sort) {
}
