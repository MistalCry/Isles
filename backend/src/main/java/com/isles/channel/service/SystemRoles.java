package com.isles.channel.service;

import java.util.List;

/**
 * 频道四个系统身份组的定义（docs/03 §2.1、FR-ROLE-01），创建频道时自动生成。
 *
 * <p>系统身份组 {@code is_system=1}，M1 不支持自定义身份组（M2）。
 * 频道主权限在 {@link ChannelAccessService} 中另有 short-circuit（owner 始终放行），
 * 这里仍挂全量权限，便于 M2 展示与导出。
 */
public final class SystemRoles {

    public static final String OWNER = "频道主";
    public static final String SUPER_ADMIN = "超级管理员";
    public static final String BOARD_ADMIN = "子板块管理员";
    public static final String MEMBER = "普通成员";

    public record Definition(String name, String description, List<String> permissions, int sort) {
    }

    private static final List<String> SUPER_ADMIN_PERMISSIONS = List.of(
            ChannelPermissions.POST_CREATE, ChannelPermissions.COMMENT_CREATE,
            ChannelPermissions.POST_PIN, ChannelPermissions.POST_ESSENCE,
            ChannelPermissions.POST_DELETE, ChannelPermissions.COMMENT_DELETE,
            ChannelPermissions.CHANNEL_SETTINGS, ChannelPermissions.MEMBER_INVITE,
            ChannelPermissions.MEMBER_REMOVE, ChannelPermissions.MEMBER_MUTE,
            ChannelPermissions.MUTE_ALL, ChannelPermissions.ROLE_MANAGE,
            ChannelPermissions.INVITE_CREATE, ChannelPermissions.INVITE_APPROVE,
            ChannelPermissions.VISIBILITY, ChannelPermissions.BOARD_MANAGE,
            ChannelPermissions.CONTENT_MANAGE, ChannelPermissions.LOG_VIEW,
            ChannelPermissions.DATA_VIEW, ChannelPermissions.AI_MANAGE);

    private static final List<String> BOARD_ADMIN_PERMISSIONS = List.of(
            ChannelPermissions.POST_CREATE, ChannelPermissions.COMMENT_CREATE,
            ChannelPermissions.POST_PIN, ChannelPermissions.POST_ESSENCE,
            ChannelPermissions.POST_DELETE, ChannelPermissions.COMMENT_DELETE,
            ChannelPermissions.CONTENT_MANAGE);

    private static final List<String> MEMBER_PERMISSIONS = List.of(
            ChannelPermissions.POST_CREATE, ChannelPermissions.COMMENT_CREATE);

    private SystemRoles() {
    }

    /**
     * 四个默认身份组，按展示顺序返回。
     */
    public static List<Definition> definitions() {
        return List.of(
                new Definition(OWNER, "频道创建者，拥有全部权限（唯一）",
                        ChannelPermissions.ALL, 0),
                new Definition(SUPER_ADMIN, "频道主之外的最高管理角色，不可解散频道",
                        SUPER_ADMIN_PERMISSIONS, 1),
                new Definition(BOARD_ADMIN, "管理指定板块的内容与互动", BOARD_ADMIN_PERMISSIONS, 2),
                new Definition(MEMBER, "频道普通成员，可发帖与评论", MEMBER_PERMISSIONS, 3));
    }
}
