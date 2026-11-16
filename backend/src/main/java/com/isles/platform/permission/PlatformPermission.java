package com.isles.platform.permission;

/**
 * 平台权限点（docs/11 §7.1）。
 *
 * <p>权限点与角色解耦：接口只声明「需要哪个权限点」，角色到权限点的映射集中在
 * {@link PlatformRole}，后续增删角色不影响控制器代码。
 */
public enum PlatformPermission {

    /** 查看平台配置。 */
    CONFIG_READ("查看平台配置"),

    /** 修改平台配置。 */
    CONFIG_WRITE("修改平台配置"),

    /** 授权/撤销平台管理员。 */
    ADMIN_MANAGE("管理平台管理员"),

    /** 查看平台审计日志。 */
    AUDIT_READ("查看审计日志"),

    /** 查看用户列表与处罚历史。 */
    USER_READ("查看用户"),

    /** 查看全站内容（帖子/评论）。 */
    CONTENT_READ("查看全站内容"),

    /** 查看全站举报。 */
    REPORT_READ("查看举报"),

    /** 封禁/解封用户。 */
    USER_BAN("封禁用户"),

    /** 创建/撤销用户处罚（禁言、警告）。 */
    USER_PENALTY("处罚用户"),

    /** 下架/恢复帖子与评论。 */
    CONTENT_TAKEDOWN("下架内容"),

    /** 处置举报。 */
    REPORT_HANDLE("处置举报"),

    /** 管理 AI 模型配置。 */
    AI_MANAGE("管理 AI 配置"),

    /** 管理平台公告（草稿/发布/归档）。 */
    ANNOUNCEMENT_MANAGE("管理平台公告"),

    /** 管理敏感词库与命中记录。 */
    WORD_MANAGE("管理敏感词库"),

    /** 查看平台数据与趋势。 */
    STATS_READ("查看平台数据"),

    /** 管理 AI 用户代理（人设/启停/运行日志/手动触发）。 */
    AGENT_MANAGE("管理 AI 代理");

    private final String label;

    PlatformPermission(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
