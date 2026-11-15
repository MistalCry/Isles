package com.isles.channel.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 频道成员（表 {@code channel_members}，docs/06 §3.5）。
 */
@Getter
@Setter
@TableName("channel_members")
public class ChannelMember {

    @TableId(type = IdType.INPUT)
    private String id;
    private String channelId;
    private String userId;
    private String roleId;
    private String status;
    /**
     * 个人禁言到期时间；NULL 表示未禁言。
     * <p>更新策略必须为 ALWAYS：解除禁言需要把该列写回 NULL，
     * MyBatis-Plus 默认 NOT_NULL 会忽略 null 字段，导致「已解除但仍被判定禁言」。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime mutedUntil;
    private LocalDateTime joinedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum Status {
        ACTIVE, MUTED, LEFT, KICKED
    }
}
