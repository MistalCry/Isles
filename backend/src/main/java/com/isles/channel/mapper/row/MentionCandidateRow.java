package com.isles.channel.mapper.row;

import lombok.Getter;
import lombok.Setter;

/**
 * @提及候选（频道活跃成员，含频道助手虚拟账号）。
 */
@Getter
@Setter
public class MentionCandidateRow {
    private String id;
    private String username;
    private String nickname;
    private String avatarUrl;
}
