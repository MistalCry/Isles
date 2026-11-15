package com.isles.search.mapper.row;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 搜索日志聚合行：关键词 + 次数 + 最近搜索时间。
 */
@Getter
@Setter
public class KeywordCountRow {

    private String keyword;
    private Long searchCount;
    private LocalDateTime lastSearchedAt;
}
