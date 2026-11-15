package com.isles.platform.audit;

/**
 * 平台审计动作常量（稳定字符串，前端按此筛选）。
 *
 * <p>命名规则 {@code platform.<领域>.<动作>}；新增动作只追加，不改已有值。
 */
public final class PlatformAuditActions {

    public static final String CONFIG_UPDATE = "platform.config.update";
    public static final String CONFIG_RESET = "platform.config.reset";

    public static final String ADMIN_GRANT = "platform.admin.grant";
    public static final String ADMIN_ROLE_CHANGE = "platform.admin.role_change";
    public static final String ADMIN_REVOKE = "platform.admin.revoke";

    public static final String USER_BAN = "platform.user.ban";
    public static final String USER_UNBAN = "platform.user.unban";
    public static final String CHANNEL_BAN = "platform.channel.ban";
    public static final String CHANNEL_UNBAN = "platform.channel.unban";

    public static final String PENALTY_CREATE = "platform.penalty.create";
    public static final String PENALTY_REVOKE = "platform.penalty.revoke";

    public static final String CONTENT_TAKEDOWN = "platform.content.takedown";
    public static final String CONTENT_RESTORE = "platform.content.restore";

    public static final String REPORT_HANDLE = "platform.report.handle";

    public static final String ANNOUNCEMENT_CREATE = "platform.announcement.create";
    public static final String ANNOUNCEMENT_UPDATE = "platform.announcement.update";
    public static final String ANNOUNCEMENT_PUBLISH = "platform.announcement.publish";
    public static final String ANNOUNCEMENT_ARCHIVE = "platform.announcement.archive";
    public static final String ANNOUNCEMENT_DELETE = "platform.announcement.delete";

    public static final String WORD_CREATE = "platform.word.create";
    public static final String WORD_UPDATE = "platform.word.update";
    public static final String WORD_DELETE = "platform.word.delete";
    public static final String WORD_IMPORT = "platform.word.import";

    public static final String HOT_KEYWORD_DELETE = "platform.hotkeyword.delete";

    private PlatformAuditActions() {
    }
}
