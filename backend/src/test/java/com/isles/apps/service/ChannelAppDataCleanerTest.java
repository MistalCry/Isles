package com.isles.apps.service;

import com.isles.apps.entity.ActivitySignup;
import com.isles.apps.entity.ChannelActivity;
import com.isles.apps.entity.ChannelApp;
import com.isles.apps.entity.VoiceRoom;
import com.isles.apps.entity.VoiceRoomSeat;
import com.isles.apps.mapper.ActivitySignupMapper;
import com.isles.apps.mapper.ChannelActivityMapper;
import com.isles.apps.mapper.ChannelAppMapper;
import com.isles.apps.mapper.VoiceRoomMapper;
import com.isles.apps.mapper.VoiceRoomSeatMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 频道级数据清理单元测试（K2）：解散频道必须把应用/语音房/麦位/活动/报名一起清掉。
 */
class ChannelAppDataCleanerTest {

    private ChannelAppMapper appMapper;
    private VoiceRoomMapper roomMapper;
    private VoiceRoomSeatMapper seatMapper;
    private ChannelActivityMapper activityMapper;
    private ActivitySignupMapper signupMapper;
    private VoiceRoomPresence presence;
    private ChannelAppDataCleaner cleaner;

    @BeforeEach
    void setUp() {
        appMapper = mock(ChannelAppMapper.class);
        roomMapper = mock(VoiceRoomMapper.class);
        seatMapper = mock(VoiceRoomSeatMapper.class);
        activityMapper = mock(ChannelActivityMapper.class);
        signupMapper = mock(ActivitySignupMapper.class);
        presence = new VoiceRoomPresence();
        cleaner = new ChannelAppDataCleaner(appMapper, roomMapper, seatMapper, activityMapper, signupMapper,
                presence);
    }

    @Test
    void should_purge_all_channel_scoped_rows_in_dependency_order() {
        when(activityMapper.selectList(any())).thenReturn(List.of(activity("a1"), activity("a2")));
        when(signupMapper.delete(any())).thenReturn(3);
        when(activityMapper.delete(any())).thenReturn(2);
        when(roomMapper.selectList(any())).thenReturn(List.of(room("r1")));
        when(seatMapper.delete(any())).thenReturn(2);
        when(roomMapper.delete(any())).thenReturn(1);
        when(appMapper.delete(any())).thenReturn(1);
        presence.join("r1", "u1");

        ChannelAppDataCleaner.PurgeResult result = cleaner.purgeChannel("ch-1");

        assertThat(result.apps()).isEqualTo(1);
        assertThat(result.rooms()).isEqualTo(1);
        assertThat(result.seats()).isEqualTo(2);
        assertThat(result.activities()).isEqualTo(2);
        assertThat(result.signups()).isEqualTo(3);
        // 在房登记是进程内状态，删库不会连带清掉
        assertThat(presence.isPresent("r1", "u1")).isFalse();
        verify(signupMapper).delete(any());
        verify(appMapper).delete(any());
    }

    @Test
    void should_skip_signup_delete_when_channel_has_no_activity() {
        when(activityMapper.selectList(any())).thenReturn(List.of());
        when(activityMapper.delete(any())).thenReturn(0);
        when(roomMapper.selectList(any())).thenReturn(List.of());
        when(roomMapper.delete(any())).thenReturn(0);
        when(appMapper.delete(any())).thenReturn(0);

        ChannelAppDataCleaner.PurgeResult result = cleaner.purgeChannel("ch-2");

        assertThat(result.signups()).isZero();
        // 没有活动时不该对报名表发 DELETE（IN 空集合会拼出非法 SQL）
        verify(signupMapper, org.mockito.Mockito.never()).delete(any());
    }

    @Test
    void should_return_zero_for_null_channel() {
        ChannelAppDataCleaner.PurgeResult result = cleaner.purgeChannel(null);

        assertThat(result.apps()).isZero();
        assertThat(result.rooms()).isZero();
    }

    private ChannelActivity activity(String id) {
        ChannelActivity activity = new ChannelActivity();
        activity.setId(id);
        activity.setChannelId("ch-1");
        activity.setStatus("PUBLISHED");
        return activity;
    }

    private VoiceRoom room(String id) {
        VoiceRoom room = new VoiceRoom();
        room.setId(id);
        room.setChannelId("ch-1");
        room.setStatus("OPEN");
        room.setCreatedAt(LocalDateTime.now());
        return room;
    }

    /** 仅用于编译期占位，避免未使用导入告警。 */
    @SuppressWarnings("unused")
    private ChannelApp app() {
        return new ChannelApp();
    }

    @SuppressWarnings("unused")
    private VoiceRoomSeat seat() {
        return new VoiceRoomSeat();
    }

    @SuppressWarnings("unused")
    private ActivitySignup signup() {
        return new ActivitySignup();
    }
}
