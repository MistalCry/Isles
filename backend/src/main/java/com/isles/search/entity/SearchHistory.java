package com.isles.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 搜索历史（表 {@code search_history}，docs/06 §3.12）：仅本人可见。
 */
@Getter
@Setter
@TableName("search_history")
public class SearchHistory {

    @TableId(type = IdType.INPUT)
    private String id;
    private String userId;
    private String keyword;
    private LocalDateTime createdAt;
}
