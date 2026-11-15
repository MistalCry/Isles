package com.isles.channel.mapper.row;

import lombok.Getter;
import lombok.Setter;

/**
 * 成员分析用行：身份组 + 成员数。
 */
@Getter
@Setter
public class RoleMemberCountRow {

    private String roleId;
    private Long memberCount;
}
