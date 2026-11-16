package com.isles.post.dto;

import java.util.List;

/**
 * 「我的帖子」分页结果（docs/05 §2.1）：与频道帖子流同构（{@link PostResponse}），
 * 但使用 page/size 偏移分页（个人主页不需要游标续拉）。
 */
public record UserPostPage(List<PostResponse> items, long total, int page, int size) {

    public UserPostPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
