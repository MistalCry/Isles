package com.isles.channel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 加入申请（表 {@code join_requests}，docs/06 §3.7）：需审批的邀请链接使用后生成。
 */
@Getter
@Setter
@TableName("join_requests")
public class JoinRequest {

    @TableId(type = IdType.INPUT)
    private String id;
    private String channelId;
    private String userId;
    private String inviteId;
    private String status;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;

    public enum Status {
        PENDING, APPROVED, REJECTED
    }
}
