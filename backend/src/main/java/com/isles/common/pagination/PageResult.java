package com.isles.common.pagination;

import java.util.List;

/**
 * 游标分页结果（docs/05 §1.1）：{@code { "items": [...], "nextCursor": "ulid|null" }}。
 */
public record PageResult<T>(List<T> items, String nextCursor) {

    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> PageResult<T> empty() {
        return new PageResult<>(List.of(), null);
    }
}
