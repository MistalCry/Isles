package com.isles.apps.service;

import com.isles.apps.dto.VoiceRoomDtos;
import com.isles.apps.entity.VoiceRoom;
import com.isles.apps.entity.VoiceRoomSeat;
import com.isles.apps.mapper.ChannelAppMapper;
import com.isles.apps.mapper.VoiceRoomMapper;
import com.isles.apps.mapper.VoiceRoomSeatMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.realtime.RealtimePublisher;
import com.isles.realtime.dto.RealtimeEvent;
import com.isles.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语音房单元测试（V10）：抢座冲突、麦位越界、关房清理与状态广播。
 */
class VoiceRoomServiceTest {

    private VoiceRoomMapper roomMapper;
    private VoiceRoomSeatMapper seatMapper;
    private ChannelAppMapper appMapper;
    private UserMapper userMapper;
    private ChannelAccessService accessService;
    private RealtimePublisher realtimePublisher;
    private VoiceRoomPresence presence;
    private VoiceRoomService service;

    @BeforeEach
    void setUp() {
        roomMapper = mock(VoiceRoomMapper.class);
        seatMapper = mock(VoiceRoomSeatMapper.class);
        appMapper = mock(ChannelAppMapper.class);
        userMapper = mock(UserMapper.class);
        accessService = mock(ChannelAccessService.class);
        realtimePublisher = mock(RealtimePublisher.class);
        presence = new VoiceRoomPresence();
        service = new VoiceRoomService(roomMapper, seatMapper, appMapper, userMapper, accessService,
                realtimePublisher, presence);
    }

    @Test
    void should_reject_when_seat_already_taken() {
        when(roomMapper.selectById("r1")).thenReturn(room("OPEN", 8));
        when(seatMapper.selectByRoomAndUser("r1", "u1")).thenReturn(null);
        when(seatMapper.insert(any(VoiceRoomSeat.class)))
                .thenThrow(new DuplicateKeyException("uk_voice_room_seats_room_seat"));

        assertThatThrownBy(() -> service.takeSeat("r1", 3, "u1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VOICE_SEAT_OCCUPIED));
    }

    @Test
    void should_reject_seat_out_of_range() {
        when(roomMapper.selectById("r1")).thenReturn(room("OPEN", 4));

        assertThatThrownBy(() -> service.takeSeat("r1", 4, "u1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VOICE_SEAT_INVALID));
        verify(seatMapper, never()).insert(any(VoiceRoomSeat.class));
    }

    @Test
    void should_take_seat_and_broadcast_state() {
        when(roomMapper.selectById("r1")).thenReturn(room("OPEN", 8));
        when(seatMapper.selectByRoomAndUser("r1", "u1")).thenReturn(null);
        when(seatMapper.insert(any(VoiceRoomSeat.class))).thenReturn(1);
        when(seatMapper.selectByRoom("r1")).thenReturn(List.of());

        VoiceRoomDtos.RoomView view = service.takeSeat("r1", 2, "u1");

        assertThat(view.status()).isEqualTo("OPEN");
        verify(seatMapper).insert(any(VoiceRoomSeat.class));
        verify(realtimePublisher).publishTopic(eq("/topic/voice/r1/state"), any(RealtimeEvent.class));
    }

    @Test
    void should_move_seat_when_already_seated_elsewhere() {
        when(roomMapper.selectById("r1")).thenReturn(room("OPEN", 8));
        VoiceRoomSeat existing = seat("r1", "u1", 1);
        when(seatMapper.selectByRoomAndUser("r1", "u1")).thenReturn(existing);
        when(seatMapper.insert(any(VoiceRoomSeat.class))).thenReturn(1);
        when(seatMapper.selectByRoom("r1")).thenReturn(List.of());

        service.takeSeat("r1", 5, "u1");

        // 换麦：先释放原麦位再抢新麦位
        verify(seatMapper).deleteByRoomAndUser("r1", "u1");
        verify(seatMapper).insert(any(VoiceRoomSeat.class));
    }

    @Test
    void should_close_room_and_clear_seats() {
        when(roomMapper.selectById("r1")).thenReturn(room("OPEN", 8));
        when(seatMapper.deleteByRoom("r1")).thenReturn(2);
        when(seatMapper.selectByRoom("r1")).thenReturn(List.of());

        VoiceRoomDtos.RoomView view = service.close("r1", "host-1");

        assertThat(view.status()).isEqualTo("CLOSED");
        verify(roomMapper).updateById(any(VoiceRoom.class));
        verify(seatMapper).deleteByRoom("r1");
        verify(realtimePublisher).publishTopic(eq("/topic/voice/r1/state"), any(RealtimeEvent.class));
    }

    @Test
    void should_reject_second_open_room_in_same_channel() {
        when(roomMapper.countOpenByHost("ch-1", "u1")).thenReturn(1L);

        assertThatThrownBy(() -> service.create("ch-1",
                new VoiceRoomDtos.CreateRoomRequest(null, "我的房间", 8), "u1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VOICE_ROOM_ALREADY_OPEN));
        verify(roomMapper, never()).insert(any(VoiceRoom.class));
    }


    // ------------------------------------------------------------------
    // 在房校验（K1）：订阅/发送共用的入口
    // ------------------------------------------------------------------

    @Test
    void should_allow_signal_when_user_joined() {
        when(roomMapper.selectById("r1")).thenReturn(room("OPEN", 8));
        presence.join("r1", "u1");

        service.requireSignalAccess("r1", "u1");

        // 命中登记后不再查麦位表（每帧查库会打爆连接通道）
        verify(seatMapper, never()).selectByRoomAndUser(anyString(), anyString());
    }

    @Test
    void should_allow_signal_when_user_on_seat_without_join() {
        when(roomMapper.selectById("r1")).thenReturn(room("OPEN", 8));
        when(seatMapper.selectByRoomAndUser("r1", "u1")).thenReturn(seat("r1", "u1", 1));

        service.requireSignalAccess("r1", "u1");

        // 上过麦也算在房，并补登记
        assertThat(presence.isPresent("r1", "u1")).isTrue();
    }

    @Test
    void should_reject_signal_when_not_in_room() {
        when(roomMapper.selectById("r1")).thenReturn(room("OPEN", 8));
        when(seatMapper.selectByRoomAndUser("r1", "u2")).thenReturn(null);

        assertThatThrownBy(() -> service.requireSignalAccess("r1", "u2"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VOICE_NOT_IN_ROOM));
    }

    @Test
    void should_reject_signal_when_room_closed_or_missing() {
        when(roomMapper.selectById("r1")).thenReturn(room("CLOSED", 8));
        when(roomMapper.selectById("r2")).thenReturn(null);

        assertThatThrownBy(() -> service.requireSignalAccess("r1", "u1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VOICE_ROOM_CLOSED));
        assertThatThrownBy(() -> service.requireSignalAccess("r2", "u1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VOICE_ROOM_CLOSED));
    }

    @Test
    void should_clear_presence_when_room_closed() {
        when(roomMapper.selectById("r1")).thenReturn(room("OPEN", 8));
        when(seatMapper.deleteByRoom("r1")).thenReturn(1);
        when(seatMapper.selectByRoom("r1")).thenReturn(List.of());
        presence.join("r1", "u1");

        service.close("r1", "host-1");

        assertThat(presence.isPresent("r1", "u1")).isFalse();
    }

    private VoiceRoom room(String status, int maxSeats) {
        VoiceRoom room = new VoiceRoom();
        room.setId("r1");
        room.setChannelId("ch-1");
        room.setHostId("host-1");
        room.setTitle("测试房间");
        room.setStatus(status);
        room.setMaxSeats(maxSeats);
        room.setCreatedAt(LocalDateTime.now());
        return room;
    }

    private VoiceRoomSeat seat(String roomId, String userId, int seatIndex) {
        VoiceRoomSeat seat = new VoiceRoomSeat();
        seat.setId("seat-1");
        seat.setRoomId(roomId);
        seat.setUserId(userId);
        seat.setSeatIndex(seatIndex);
        seat.setMuted(false);
        seat.setJoinedAt(LocalDateTime.now());
        return seat;
    }
}
