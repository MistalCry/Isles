package com.isles.post.mapper.row;

import lombok.Getter;
import lombok.Setter;

/**
 * 内容分析用行：板块 + 帖子数。
 */
@Getter
@Setter
public class BoardPostCountRow {

    private String boardId;
    private String boardName;
    private Long postCount;
}
