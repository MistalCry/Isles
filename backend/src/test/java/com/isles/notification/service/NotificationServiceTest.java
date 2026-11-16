package com.isles.notification.service;

import com.isles.common.json.JsonSupport;
import com.isles.notification.entity.Notification;
import com.isles.notification.mapper.NotificationMapper;
import com.isles.realtime.RealtimeDispatch;
import com.isles.realtime.RealtimePublisher;
import com.isles.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知服务群发单元测试：按 id 游标分批、单批 ≤ 500、payload 落库。
 */
class NotificationServiceTest {

    private NotificationMapper notificationMapper;
    private UserMapper userMapper;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        notificationMapper = mock(NotificationMapper.class);
        userMapper = mock(UserMapper.class);
        service = new NotificationService(notificationMapper, userMapper, new JsonSupport(),
                mock(RealtimePublisher.class), mock(RealtimeDispatch.class));
    }

    @Test
    void should_broadcast_in_batches_without_one_giant_insert() {
        // 第一批 500 人（满批继续），第二批 10 人（不满批结束）
        List<String> firstBatch = userIds("u", 500);
        List<String> secondBatch = userIds("v", 10);
        when(userMapper.selectActiveIdsAfter(isNull(), anyInt())).thenReturn(firstBatch);
        when(userMapper.selectActiveIdsAfter(eq(firstBatch.get(firstBatch.size() - 1)), anyInt()))
                .thenReturn(secondBatch);
        when(notificationMapper.insertBatch(any())).thenReturn(1);

        int total = service.broadcastSystem(Notification.Type.SYSTEM, "维护通知", "今晚 22 点维护",
                Map.of("announcementId", "ann-1"));

        assertThat(total).isEqualTo(510);
        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationMapper, times(2)).insertBatch(captor.capture());
        List<List<Notification>> batches = captor.getAllValues();
        assertThat(batches.get(0)).hasSize(500);
        assertThat(batches.get(1)).hasSize(10);
        Notification first = batches.get(0).get(0);
        assertThat(first.getUserId()).isEqualTo("u0");
        assertThat(first.getType()).isEqualTo(Notification.Type.SYSTEM.name());
        assertThat(first.getCategory()).isEqualTo(Notification.Category.SYSTEM.name());
        assertThat(first.getTitle()).isEqualTo("维护通知");
        assertThat(first.getPayload()).contains("ann-1");
        assertThat(first.getIsRead()).isFalse();
    }

    @Test
    void should_do_nothing_when_no_active_users() {
        when(userMapper.selectActiveIdsAfter(any(), anyInt())).thenReturn(List.of());

        int total = service.broadcastSystem(Notification.Type.SYSTEM, "标题", "内容", null);

        assertThat(total).isZero();
        verify(notificationMapper, org.mockito.Mockito.never()).insertBatch(any());
    }

    @Test
    void should_use_null_payload_when_payload_empty() {
        when(userMapper.selectActiveIdsAfter(isNull(), anyInt())).thenReturn(List.of("u1"));
        when(notificationMapper.insertBatch(any())).thenReturn(1);

        service.broadcastSystem(Notification.Type.SYSTEM, "标题", "内容", Map.of());

        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationMapper).insertBatch(captor.capture());
        assertThat(captor.getValue().get(0).getPayload()).isNull();
    }

    private List<String> userIds(String prefix, int count) {
        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(prefix + i);
        }
        return ids;
    }
}
