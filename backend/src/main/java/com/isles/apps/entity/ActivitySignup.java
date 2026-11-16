package com.isles.apps.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 活动报名（表 {@code activity_signups}，V10）：一用户一条，取消即改状态（便于恢复与统计）。
 */
@Getter
@Setter
@TableName("activity_signups")
public class ActivitySignup {

    @TableId(type = IdType.INPUT)
    private String id;
    private String activityId;
    private String userId;
    private String status;
    private LocalDateTime createdAt;

    public enum Status {
        GOING, CANCELLED
    }
}
