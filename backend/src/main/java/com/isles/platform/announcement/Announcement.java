package com.isles.platform.announcement;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 平台公告（表 {@code announcements}，docs/11 §4.1）。
 *
 * <p>生效判定：{@code status=PUBLISHED} 且当前时间落在 {@code [start_at, end_at)} 内（两端可空）。
 */
@Getter
@Setter
@TableName("announcements")
public class Announcement {

    @TableId(type = IdType.INPUT)
    private String id;
    private String title;
    /** 纯文本，前台按文本渲染（不做 HTML 渲染，避免 XSS）。 */
    private String content;
    private String level;
    private String placement;
    private String status;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum Level {
        INFO, WARNING, CRITICAL
    }

    public enum Placement {
        BANNER, MODAL, INBOX
    }

    public enum Status {
        DRAFT, PUBLISHED, ARCHIVED
    }
}
