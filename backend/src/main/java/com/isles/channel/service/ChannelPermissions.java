package com.isles.channel.service;

import java.util.List;

/**
 * 频道权限点常量（docs/03 §2.2、docs/07 §3）。
 *
 * <p>身份组的 {@code permissions} 列存这些字符串的 JSON 数组。权限判断集中在
 * {@link ChannelAccessService}，禁止在业务代码中散落字符串字面量。
 */
public final class ChannelPermissions {

    public static final String POST_CREATE = "post.create";
    public static final String COMMENT_CREATE = "comment.create";
    public static final String POST_PIN = "post.pin";
    public static final String POST_ESSENCE = "post.essence";
    public static final String POST_DELETE = "post.delete";
    public static final String COMMENT_DELETE = "comment.delete";
    public static final String CHANNEL_SETTINGS = "channel.settings";
    public static final String MEMBER_INVITE = "channel.member.invite";
    public static final String MEMBER_REMOVE = "channel.member.remove";
    public static final String MEMBER_MUTE = "channel.member.mute";
    public static final String MUTE_ALL = "channel.mute_all";
    public static final String ROLE_MANAGE = "channel.role.manage";
    public static final String INVITE_CREATE = "channel.invite.create";
    public static final String INVITE_APPROVE = "channel.invite.approve";
    public static final String VISIBILITY = "channel.visibility";
    public static final String BOARD_MANAGE = "channel.board.manage";
    public static final String CONTENT_MANAGE = "channel.content.manage";
    public static final String LOG_VIEW = "channel.log.view";
    public static final String DATA_VIEW = "channel.data.view";
    public static final String AI_MANAGE = "channel.ai.manage";
    public static final String DISSOLVE = "channel.dissolve";

    public static final List<String> ALL = List.of(
            POST_CREATE, COMMENT_CREATE, POST_PIN, POST_ESSENCE, POST_DELETE, COMMENT_DELETE,
            CHANNEL_SETTINGS, MEMBER_INVITE, MEMBER_REMOVE, MEMBER_MUTE, MUTE_ALL, ROLE_MANAGE,
            INVITE_CREATE, INVITE_APPROVE, VISIBILITY, BOARD_MANAGE, CONTENT_MANAGE,
            LOG_VIEW, DATA_VIEW, AI_MANAGE, DISSOLVE);

    private ChannelPermissions() {
    }
}
