package com.isles.manage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量内容操作：PIN/UNPIN/ESSENCE/UNESSENCE/DELETE（FR-MGR-05）。
 */
public record BatchPostRequest(
        @NotBlank(message = "批量操作类型不能为空")
        @Pattern(regexp = "PIN|UNPIN|ESSENCE|UNESSENCE|DELETE", message = "批量操作类型非法")
        String action,

        @NotEmpty(message = "帖子列表不能为空")
        @Size(max = 50, message = "单次最多操作 50 条帖子")
        List<String> postIds) {
}
