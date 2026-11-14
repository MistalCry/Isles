package com.isles.channel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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
    private LocalDateTime mutedUntil;
    private LocalDateTime joinedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum Status {
        ACTIVE, MUTED, LEFT, KICKED
    }
}
