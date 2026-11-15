package com.isles.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 搜索热词聚合（表 {@code hot_keywords}，V2）：由 search_logs 聚合写入，
 * 绝对值覆盖写入保证重复聚合不重复计数。
 */
@Getter
@Setter
@TableName("hot_keywords")
public class HotKeyword {

    @TableId(type = IdType.INPUT)
    private String id;
    private String keyword;
    private Long searchCount;
    private LocalDateTime lastSearchedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
