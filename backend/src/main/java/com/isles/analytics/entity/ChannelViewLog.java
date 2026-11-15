package com.isles.analytics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 频道访问日志（表 {@code channel_view_logs}，V2）：登录用户访问频道页/帖子详情时记录，
 * 作为数据中心 UV/PV 的聚合数据源（单机无 Redis HLL 的等价实现）。
 */
@Getter
@Setter
@TableName("channel_view_logs")
public class ChannelViewLog {

    @TableId(type = IdType.INPUT)
    private String id;
    private String channelId;
    private String userId;
    private String postId;
    private LocalDateTime createdAt;
}
