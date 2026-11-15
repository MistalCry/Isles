package com.isles.notification.dto;

import java.util.Map;

/**
 * 未读数统计（前端侧边栏/通知中心的红点）。
 */
public record UnreadCountResponse(long unreadCount, Map<String, Long> byCategory) {
}
