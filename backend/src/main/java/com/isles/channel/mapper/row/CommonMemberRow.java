package com.isles.channel.mapper.row;

import lombok.Getter;
import lombok.Setter;

/**
 * 推荐规则用行：频道 + 共同成员数。
 */
@Getter
@Setter
public class CommonMemberRow {

    private String channelId;
    private Long commonCount;
}
