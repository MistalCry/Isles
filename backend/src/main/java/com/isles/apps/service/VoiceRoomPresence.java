package com.isles.apps.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语音房「在房」登记表（进程内）。
 *
 * <p>为什么需要它：语音房的听众不落库（避免僵尸在线与心跳清理任务，见 {@code VoiceRoomService} 类注释），
 * 但 STOMP 信令必须能判断「这个连接的主人是否真的在房里」。所以用一张进程内的登记表记录
 * 「谁调用过 {@code POST /voice-rooms/{id}/join}（或上过麦）」，并在关房时整房清空。
 *
 * <p>约束与已知限制：
 * <ul>
 *   <li>登记项有 {@link #TTL} 滑动过期（每次访问刷新），断线不主动清理也不会永久占用；</li>
 *   <li><b>多实例部署各实例独立登记</b>：用户 A 在实例 1 join、信令落到实例 2 时会被判为不在房。
 *       当前部署为单实例，多实例需要换成 Redis 或粘性会话（已在 docs/07 §5.1 标注）。</li>
 * </ul>
 */
@Component
public class VoiceRoomPresence {

    private static final Logger log = LoggerFactory.getLogger(VoiceRoomPresence.class);

    /** 登记有效期：4 小时（远超单次语音房时长，靠滑动刷新续期）。 */
    private static final Duration TTL = Duration.ofHours(4);
    /** 房间数上限，超出时清理过期项，避免内存无界增长。 */
    private static final int MAX_ROOMS = 2_000;

    private final Map<String, Map<String, Long>> rooms = new ConcurrentHashMap<>();

    /**
     * 登记「某用户在该房间」。
     */
    public void join(String roomId, String userId) {
        if (roomId == null || userId == null) {
            return;
        }
        if (rooms.size() > MAX_ROOMS) {
            purgeExpired();
        }
        rooms.computeIfAbsent(roomId, key -> new ConcurrentHashMap<>())
                .put(userId, System.currentTimeMillis());
    }

    /**
     * 主动离开（仅下麦不调用：下麦后仍是听众，仍在房）。
     */
    public void leave(String roomId, String userId) {
        Map<String, Long> members = rooms.get(roomId);
        if (members == null) {
            return;
        }
        members.remove(userId);
        if (members.isEmpty()) {
            rooms.remove(roomId, members);
        }
    }

    /**
     * 关房：整房清空。
     */
    public void clearRoom(String roomId) {
        if (roomId != null) {
            rooms.remove(roomId);
        }
    }

    /**
     * 是否在房（命中时刷新过期时间）。
     */
    public boolean isPresent(String roomId, String userId) {
        if (roomId == null || userId == null) {
            return false;
        }
        Map<String, Long> members = rooms.get(roomId);
        if (members == null) {
            return false;
        }
        Long joinedAt = members.get(userId);
        if (joinedAt == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - joinedAt > TTL.toMillis()) {
            members.remove(userId);
            return false;
        }
        members.put(userId, now);
        return true;
    }

    /**
     * 当前在房人数（仅用于日志/诊断）。
     */
    public int size(String roomId) {
        Map<String, Long> members = rooms.get(roomId);
        return members == null ? 0 : members.size();
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        long ttl = TTL.toMillis();
        rooms.entrySet().removeIf(entry -> {
            entry.getValue().entrySet().removeIf(item -> now - item.getValue() > ttl);
            return entry.getValue().isEmpty();
        });
        log.debug("语音房在房登记表清理完成 rooms={}", rooms.size());
    }
}
