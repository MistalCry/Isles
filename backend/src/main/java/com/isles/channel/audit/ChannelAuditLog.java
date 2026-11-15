package com.isles.channel.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 频道管理日志（表 {@code channel_audit_logs}，V2）。记录频道内写操作与关键管理操作，
 * 与统计聚合分离（docs/03 §8.3）。
 */
@Getter
@Setter
@TableName("channel_audit_logs")
public class ChannelAuditLog {

    @TableId(type = IdType.INPUT)
    private String id;
    private String channelId;
    private String actorId;
    private String action;
    private String targetType;
    private String targetId;
    /** JSON 字符串。 */
    private String detail;
    private String ip;
    private LocalDateTime createdAt;

    /** 日志动作常量，保证写入与查询使用同一套稳定字符串。 */
    public static final class Action {
        public static final String CHANNEL_CREATE = "channel.create";
        public static final String CHANNEL_SETTINGS = "channel.settings";
        public static final String CHANNEL_DISSOLVE = "channel.dissolve";
        public static final String CHANNEL_MUTE_ALL = "channel.mute_all";
        public static final String CHANNEL_UNMUTE_ALL = "channel.unmute_all";
        public static final String MEMBER_JOIN = "member.join";
        public static final String MEMBER_LEAVE = "member.leave";
        public static final String MEMBER_REMOVE = "member.remove";
        public static final String MEMBER_MUTE = "member.mute";
        public static final String MEMBER_UNMUTE = "member.unmute";
        public static final String MEMBER_ROLE_CHANGE = "member.role_change";
        public static final String ROLE_CREATE = "role.create";
        public static final String ROLE_UPDATE = "role.update";
        public static final String ROLE_DELETE = "role.delete";
        public static final String BOARD_CREATE = "board.create";
        public static final String BOARD_UPDATE = "board.update";
        public static final String BOARD_DELETE = "board.delete";
        public static final String BOARD_REORDER = "board.reorder";
        public static final String INVITE_CREATE = "invite.create";
        public static final String INVITE_REVOKE = "invite.revoke";
        public static final String INVITE_ACCEPT = "invite.accept";
        public static final String JOIN_APPROVE = "join.approve";
        public static final String JOIN_REJECT = "join.reject";
        public static final String POST_CREATE = "post.create";
        public static final String POST_UPDATE = "post.update";
        public static final String POST_DELETE = "post.delete";
        public static final String POST_PIN = "post.pin";
        public static final String POST_UNPIN = "post.unpin";
        public static final String POST_ESSENCE = "post.essence";
        public static final String POST_UNESSENCE = "post.unessence";
        public static final String POST_BATCH = "post.batch";
        public static final String POST_SHARE = "post.share";
        public static final String COMMENT_CREATE = "comment.create";
        public static final String COMMENT_DELETE = "comment.delete";
        public static final String REPORT_CREATE = "report.create";
        public static final String REPORT_HANDLE = "report.handle";

        private Action() {
        }
    }
}
