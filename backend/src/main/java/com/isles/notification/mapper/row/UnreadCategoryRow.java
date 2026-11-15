package com.isles.notification.mapper.row;

import lombok.Getter;
import lombok.Setter;

/**
 * 通知未读分类统计行。
 */
@Getter
@Setter
public class UnreadCategoryRow {

    private String category;
    private Long unreadCount;
}
