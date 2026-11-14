package com.isles.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 刷新令牌（表 {@code refresh_tokens}，docs/06 §3.2）：只存 SHA-256 哈希，可撤销、可轮换。
 */
@Getter
@Setter
@TableName("refresh_tokens")
public class RefreshToken {

    @TableId(type = IdType.INPUT)
    private String id;
    private String userId;
    private String tokenHash;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private String userAgent;
    private String ip;
    private LocalDateTime createdAt;
}
