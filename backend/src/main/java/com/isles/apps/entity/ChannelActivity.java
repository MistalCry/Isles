package com.isles.apps.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 频道活动（表 {@code channel_activities}，V10）。
 *
 * <p>{@code reminderSentAt} 是「开始前 30 分钟提醒」的幂等标记：定时任务先抢占该字段再发通知，
 * 多实例/重跑都不会重复提醒。
 */
@Getter
@Setter
@TableName("channel_activities")
public class ChannelActivity {

    @TableId(type = IdType.INPUT)
    private String id;
    private String channelId;
    private String title;
    private String description;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String location;
    private Integer capacity;
    private String status;
    private LocalDateTime reminderSentAt;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum Status {
        DRAFT, PUBLISHED, CANCELLED
    }
}
