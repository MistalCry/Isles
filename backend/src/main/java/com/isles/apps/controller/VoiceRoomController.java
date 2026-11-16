package com.isles.apps.controller;

import com.isles.apps.dto.VoiceRoomDtos;
import com.isles.apps.service.VoiceRoomService;
import com.isles.common.security.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 语音房接口（V10 / docs/05 §2.14）。
 *
 * <p>信令不走 REST：客户端连 STOMP 后发 {@code /app/voice/{roomId}/signal}，
 * 服务端转发到 {@code /topic/voice/{roomId}/signal}，状态变更广播到 {@code /topic/voice/{roomId}/state}。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class VoiceRoomController {

    private final VoiceRoomService voiceRoomService;
    private final CurrentUserProvider currentUserProvider;

    /** 频道内开启中的房间列表。 */
    @GetMapping("/channels/{channelId}/voice-rooms")
    public List<VoiceRoomDtos.RoomView> list(@PathVariable String channelId) {
        return voiceRoomService.listOpen(channelId, currentUserProvider.currentUserIdOrNull());
    }

    /** 开房：房主为当前用户；同一频道一人同时只能有一个开启中的房间。 */
    @PostMapping("/channels/{channelId}/voice-rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public VoiceRoomDtos.RoomView create(@PathVariable String channelId,
                                         @Valid @RequestBody VoiceRoomDtos.CreateRoomRequest request) {
        return voiceRoomService.create(channelId, request, currentUserProvider.requireUserId());
    }

    @GetMapping("/voice-rooms/{roomId}")
    public VoiceRoomDtos.RoomView detail(@PathVariable String roomId) {
        return voiceRoomService.detail(roomId, currentUserProvider.currentUserIdOrNull());
    }

    /** 进房（成员校验；听众不落库）。 */
    @PostMapping("/voice-rooms/{roomId}/join")
    public VoiceRoomDtos.RoomView join(@PathVariable String roomId) {
        return voiceRoomService.join(roomId, currentUserProvider.requireUserId());
    }

    /** 上麦：指定麦位，抢座冲突返回 409。 */
    @PostMapping("/voice-rooms/{roomId}/seats")
    public VoiceRoomDtos.RoomView takeSeat(@PathVariable String roomId,
                                           @Valid @RequestBody VoiceRoomDtos.TakeSeatRequest request) {
        return voiceRoomService.takeSeat(roomId, request.seatIndex(), currentUserProvider.requireUserId());
    }

    /** 下麦（释放自己的麦位）。 */
    @DeleteMapping("/voice-rooms/{roomId}/seats/me")
    public VoiceRoomDtos.RoomView leaveSeat(@PathVariable String roomId) {
        return voiceRoomService.leaveSeat(roomId, currentUserProvider.requireUserId());
    }

    /** 静音/取消静音：房主可操作任何人，其他人只能操作自己。 */
    @PostMapping("/voice-rooms/{roomId}/seats/{seatIndex}/mute")
    public VoiceRoomDtos.RoomView mute(@PathVariable String roomId, @PathVariable int seatIndex,
                                       @Valid @RequestBody VoiceRoomDtos.MuteSeatRequest request) {
        return voiceRoomService.muteSeat(roomId, seatIndex, request.muted(),
                currentUserProvider.requireUserId());
    }

    /** 关房：房主或 {@code channel.settings} 权限；清空麦位并广播 ROOM_CLOSED。 */
    @PostMapping("/voice-rooms/{roomId}/close")
    public VoiceRoomDtos.RoomView close(@PathVariable String roomId) {
        return voiceRoomService.close(roomId, currentUserProvider.requireUserId());
    }
}
