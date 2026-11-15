package com.isles.manage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 内容举报（表 {@code reports}，V2）。用户举报帖子/评论，频道管理员处置（平台管理员处置由 WP6 接入）。
 */
@Getter
@Setter
@TableName("reports")
public class Report {

    @TableId(type = IdType.INPUT)
    private String id;
    private String reporterId;
    private String channelId;
    private String targetType;
    private String targetId;
    private String reason;
    private String description;
    private String status;
    private String handlerId;
    private String handleAction;
    private String handleNote;
    private LocalDateTime handledAt;
    private LocalDateTime createdAt;

    public enum TargetType {
        POST, COMMENT
    }

    public enum Status {
        PENDING, ACCEPTED, REJECTED
    }

    public enum HandleAction {
        NONE, DELETE_CONTENT, MUTE_AUTHOR
    }

    /** 举报原因白名单（前端下拉与后端校验共用）。 */
    public enum Reason {
        SPAM, ABUSE, PORN, ILLEGAL, OTHER
    }
}
