package com.isles.dm.dto;

import java.time.Instant;

/**
 * 已读回执实时推送负载（{@code /user/queue/dm} 的 data 字段，type=DM_READ）。
 *
 * @param conversationId 会话 ID
 * @param readerId       阅读方用户 ID
 * @param readCount      本次标记已读的消息数
 * @param readAt         已读时间
 */
public record DmReadEvent(
        String conversationId,
        String readerId,
        int readCount,
        Instant readAt) {
}
