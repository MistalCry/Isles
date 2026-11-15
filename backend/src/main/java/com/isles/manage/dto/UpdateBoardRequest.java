package com.isles.manage.dto;

import jakarta.validation.constraints.Size;

/**
 * 编辑板块。
 */
public record UpdateBoardRequest(
        @Size(min = 1, max = 32, message = "板块名长度需为 1–32 个字符") String name,
        @Size(max = 200, message = "板块描述最长 200 个字符") String description,
        Integer sort) {
}
