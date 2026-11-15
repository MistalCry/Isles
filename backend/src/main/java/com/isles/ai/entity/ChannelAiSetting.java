package com.isles.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 频道助手设置（表 {@code channel_ai_settings}，docs/03 FR-MGR-08 / FR-ASSIST-01）。
 *
 * <p>一个频道最多一条；包含虚拟助手账号的 persona（昵称/头像/封面/简介/设定）与
 * 自动任务开关、每日频率上限。时间参数按 UTC。
 */
@Getter
@Setter
@TableName("channel_ai_settings")
public class ChannelAiSetting {

    @TableId(type = IdType.INPUT)
    private String id;
    private String channelId;
    private String assistantUserId;
    private String nickname;
    private String avatarUrl;
    private String coverUrl;
    private String bio;
    private String persona;
    /** 频道助手使用的模型配置；空表示跟随平台默认（V8） */
    private String modelConfigId;
    private Boolean enabled;
    private Boolean summaryDailyEnabled;
    private Boolean summaryWeeklyEnabled;
    private Boolean monthlyContributorsEnabled;
    private Boolean autoPostEnabled;
    private Boolean autoCommentEnabled;
    private Boolean autoLikeEnabled;
    /** 有人回复助手评论时自动接话（V5） */
    private Boolean autoReplyEnabled;
    private Integer dailySummaryHour;
    private Integer autoPostPerDay;
    private Integer autoCommentPerDay;
    private Integer autoLikePerDay;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
