package com.isles.platform.penalty;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户处罚记录（表 {@code user_penalties}，docs/11 §4.1）。
 *
 * <p>与 {@code users.status} 分工：{@code status} 表达「当前是否可登录」，本表保留期限、原因与历史，
 * 支持撤销与追溯。{@code end_at=null} 表示永久。
 */
@Getter
@Setter
@TableName("user_penalties")
public class UserPenalty {

    @TableId(type = IdType.INPUT)
    private String id;
    private String userId;
    private String type;
    private String scope;
    private String channelId;
    private String reason;
    private String reportId;
    private String operatorId;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String status;
    private String revokedBy;
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;

    public enum Type {
        BAN, MUTE, WARN
    }

    public enum Scope {
        GLOBAL, CHANNEL
    }

    public enum Status {
        ACTIVE, EXPIRED, REVOKED
    }
}
