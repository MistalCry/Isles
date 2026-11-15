package com.isles.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户账号（表 {@code users}，docs/06 §3.1）。
 */
@Getter
@Setter
@TableName("users")
public class User {

    @TableId(type = IdType.INPUT)
    private String id;
    private String username;
    private String passwordHash;
    private String nickname;
    private String avatarUrl;
    private String signature;
    private String gender;
    private String region;
    private String status;
    /**
     * 平台角色（V3 新增列，USER/ADMIN）。仅作为「引导超管」的兼容标记；
     * 日常平台授权走 {@code platform_admin_grants}（docs/11 §7.1）。
     */
    private String platformRole;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public enum Status {
        ACTIVE, BANNED, DISABLED
    }

    public enum Gender {
        MALE, FEMALE, UNKNOWN
    }
}
