package com.isles.channel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 频道板块（表 {@code boards}，docs/06 §3.6）：创建频道时自动生成默认板块。
 */
@Getter
@Setter
@TableName("boards")
public class Board {

    @TableId(type = IdType.INPUT)
    private String id;
    private String channelId;
    private String name;
    private String description;
    private Integer sort;
    private Long postCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 软删除（V2 新增）：板块删除后历史帖子仍可展示板块名。 */
    private LocalDateTime deletedAt;
}
