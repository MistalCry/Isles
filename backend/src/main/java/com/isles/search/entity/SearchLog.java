package com.isles.search.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 搜索日志（表 {@code search_logs}，docs/06 §3.12）：用于后续热词聚合，M1 只写入。
 */
@Getter
@Setter
@TableName("search_logs")
public class SearchLog {

    @TableId(type = IdType.INPUT)
    private String id;
    private String userId;
    private String keyword;
    private String scope;
    private String channelId;
    private Integer resultCount;
    private LocalDateTime createdAt;

    public enum Scope {
        GLOBAL, CHANNEL
    }
}
