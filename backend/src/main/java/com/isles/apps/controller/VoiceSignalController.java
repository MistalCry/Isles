package com.isles.apps.controller;

import com.isles.apps.entity.VoiceRoom;
import com.isles.apps.service.VoiceRoomService;
import com.isles.channel.service.ChannelAccessService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.realtime.RealtimePublisher;
import com.isles.realtime.StompPrincipal;
import com.isles.realtime.dto.RealtimeEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语音房信令转发（V10 / docs/05 §2.14）：
 * {@code /app/voice/{roomId}/signal} → 广播到 {@code /topic/voice/{roomId}/signal}，
 * 同时向 {@code /topic/voice/{roomId}/state} 发一条 {@code SIGNAL_RELAY} 轻量事件。
 *
 * <p>安全：CONNECT 阶段已鉴权（Principal = userId）；这里再校验「房间存在且开启 + 调用者是频道成员」，
 * 防止非成员靠猜 roomId 混进信令通道。
 *
 * <p>为什么不转发前校验 payload：信令内容（offer/answer/candidate）是端到端的，
 * 服务端只做透传与身份标注，不解析 SDP，避免耦合 WebRTC 细节。
 */
@Controller
@RequiredArgsConstructor
public class VoiceSignalController {

    private static final Logger log = LoggerFactory.getLogger(VoiceSignalController.class);
    private static final String TOPIC_SIGNAL = "/topic/voice/";
    private static final int MAX_PAYLOAD_KEYS = 32;

    private final VoiceRoomService voiceRoomService;
    private final ChannelAccessService accessService;
    private final RealtimePublisher realtimePublisher;

    @MessageMapping("/voice/{roomId}/signal")
    public void relay(@DestinationVariable String roomId, @Payload Map<String, Object> payload,
                      Principal principal) {
        String userId = principal instanceof StompPrincipal stomp ? stomp.userId() : null;
        if (userId == null) {
            log.debug("语音信令被拒绝：未鉴权连接 roomId={}", roomId);
            return;
        }
        VoiceRoom room;
        try {
            room = voiceRoomService.requireOpenRoomForSignal(roomId);
            accessService.requireMemberContext(room.getChannelId(), userId);
        } catch (AppException ex) {
            log.debug("语音信令被拒绝 roomId={} userId={} code={}", roomId, userId, ex.getErrorCode());
            return;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("roomId", roomId);
        envelope.put("from", userId);
        envelope.put("data", sanitize(payload));
        realtimePublisher.publishTopic(TOPIC_SIGNAL + roomId + "/signal",
                RealtimeEvent.of("VOICE_SIGNAL", envelope));

        // 状态主题同时收到一条轻量事件：订阅者据此感知「房间有活动」，无需再查库
        Map<String, Object> stateHint = new LinkedHashMap<>();
        stateHint.put("event", "SIGNAL_RELAY");
        stateHint.put("roomId", roomId);
        stateHint.put("from", userId);
        stateHint.put("signalType", payload == null ? null : payload.get("type"));
        realtimePublisher.publishTopic(TOPIC_SIGNAL + roomId + "/state",
                RealtimeEvent.of("VOICE_STATE", stateHint));
    }

    /**
     * 只保留有限的键，避免客户端塞入超大对象撑爆帧上限（16KB）。
     */
    private Map<String, Object> sanitize(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (count++ >= MAX_PAYLOAD_KEYS) {
                break;
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
