package com.isles.realtime.dto;

import com.isles.common.id.UlidGenerator;

import java.time.Instant;

/**
 * 实时推送事件信封（docs/00 §10：消息含 {@code id/type/ts/version}，便于演进与兼容）。
 *
 * <p>统一信封后，前端只需按 {@code type} 分发、按 {@code id} 去重；断线重连后仍以 REST 游标补拉为准，
 * 推送本身不作为唯一数据来源（docs/02 §5.4）。
 *
 * @param id      事件 ID（ULID），客户端去重用
 * @param type    事件类型，如 {@code DM_MESSAGE}/{@code DM_READ}/{@code FRIEND_REQUEST}
 * @param ts      事件产生时间（UTC）
 * @param version 信封版本，新增字段不升版本，语义变更才升
 * @param data    事件负载，随 type 而定
 */
public record RealtimeEvent(String id, String type, Instant ts, int version, Object data) {

    public static final int CURRENT_VERSION = 1;

    public static RealtimeEvent of(String type, Object data) {
        return new RealtimeEvent(UlidGenerator.generate(), type, Instant.now(), CURRENT_VERSION, data);
    }
}
