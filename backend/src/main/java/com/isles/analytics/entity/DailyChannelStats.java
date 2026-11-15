package com.isles.analytics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 频道日聚合统计（表 {@code daily_channel_stats}，V2）。
 *
 * <p>唯一键 (channel_id, stat_date) + upsert 保证聚合任务可重复执行不重复计数（docs/03 §8.3）。
 */
@Getter
@Setter
@TableName("daily_channel_stats")
public class DailyChannelStats {

    @TableId(type = IdType.INPUT)
    private String id;
    private String channelId;
    private LocalDate statDate;
    private Long memberCount;
    private Long newMembers;
    private Long lostMembers;
    private Long visitUv;
    private Long postViews;
    private Long activeUsers;
    private BigDecimal activeRate;
    private Long posts;
    private Long comments;
    private Long likes;
    private Long shares;
    private Long interactions;
    private BigDecimal interactionRate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
