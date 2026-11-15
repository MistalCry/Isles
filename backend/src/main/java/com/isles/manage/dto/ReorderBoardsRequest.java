package com.isles.manage.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 板块排序（按传入顺序重排）。
 */
public record ReorderBoardsRequest(
        @NotEmpty(message = "板块顺序不能为空")
        @Size(max = 100, message = "板块数量超出限制")
        List<String> boardIds) {
}
