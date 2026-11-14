package com.isles.channel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 频道邀请链接（表 {@code channel_invites}，docs/06 §3.7）：只存 token 的 SHA-256 哈希。
 */
@Getter
@Setter
@TableName("channel_invites")
public class ChannelInvite {

    @TableId(type = IdType.INPUT)
    private String id;
    private String channelId;
    private String tokenHash;
    private Boolean requiresApproval;
    private LocalDateTime expiresAt;
    private Integer maxUses;
    private Integer usedCount;
    private String createdBy;
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;
}
