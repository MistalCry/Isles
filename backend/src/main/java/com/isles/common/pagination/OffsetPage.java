package com.isles.common.pagination;

import java.util.List;

/**
 * 偏移分页结果（管理端列表使用：docs/05 约定管理列表用 page/size，返回 items + total）。
 */
public record OffsetPage<T>(List<T> items, long total) {

    public OffsetPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
