package com.isles.post.mapper.row;

import lombok.Getter;
import lombok.Setter;

/**
 * 推荐规则用行：频道 + 时间窗内新增帖子数。
 */
@Getter
@Setter
public class ChannelPostCountRow {

    private String channelId;
    private Long postCount;
}
