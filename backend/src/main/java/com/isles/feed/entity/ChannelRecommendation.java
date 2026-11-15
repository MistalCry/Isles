package com.isles.feed.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 频道推荐结果（表 {@code channel_recommendations}，V2）：规则打分生成、按用户存储。
 */
@Getter
@Setter
@TableName("channel_recommendations")
public class ChannelRecommendation {

    @TableId(type = IdType.INPUT)
    private String id;
    private String userId;
    private String channelId;
    private BigDecimal score;
    private String reason;
    private LocalDateTime generatedAt;
}
