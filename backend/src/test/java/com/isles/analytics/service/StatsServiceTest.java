package com.isles.analytics.service;

import com.isles.analytics.dto.DailyStatsResponse;
import com.isles.analytics.entity.DailyChannelStats;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.mapper.ChannelMemberMapper;
import com.isles.channel.mapper.ChannelStatsMapper;
import com.isles.channel.mapper.ChannelViewLogMapper;
import com.isles.channel.mapper.DailyChannelStatsMapper;
import com.isles.channel.mapper.RoleMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.post.mapper.PostMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
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
 * 数据中心聚合单测：指标口径计算、活跃率/互动率、重复聚合保留原记录（不重复计数）。
 */
class StatsServiceTest {

    private ChannelStatsMapper channelStatsMapper;
    private ChannelViewLogMapper channelViewLogMapper;
    private DailyChannelStatsMapper dailyChannelStatsMapper;
    private ChannelMapper channelMapper;
    private ChannelMemberMapper channelMemberMapper;
    private RoleMapper roleMapper;
    private PostMapper postMapper;
    private ChannelAccessService accessService;
    private StatsService service;

    @BeforeEach
    void setUp() {
        channelStatsMapper = mock(ChannelStatsMapper.class);
        channelViewLogMapper = mock(ChannelViewLogMapper.class);
        dailyChannelStatsMapper = mock(DailyChannelStatsMapper.class);
        channelMapper = mock(ChannelMapper.class);
        channelMemberMapper = mock(ChannelMemberMapper.class);
        roleMapper = mock(RoleMapper.class);
        postMapper = mock(PostMapper.class);
        accessService = mock(ChannelAccessService.class);
        service = new StatsService(channelStatsMapper, channelViewLogMapper, dailyChannelStatsMapper,
                channelMapper, channelMemberMapper, roleMapper, postMapper, accessService);
    }

    @Test
    void should_aggregate_daily_metrics_and_rates() {
        LocalDate date = LocalDate.of(2026, 11, 13);
        when(channelStatsMapper.countActiveMembers("ch-1")).thenReturn(10L);
        when(channelStatsMapper.countMembersJoinedBetween(eq("ch-1"), any(), any())).thenReturn(2L);
        when(channelStatsMapper.countMembersLeftBetween(eq("ch-1"), any(), any())).thenReturn(1L);
        when(channelViewLogMapper.countViews(eq("ch-1"), any(), any())).thenReturn(5L);
        when(channelViewLogMapper.countDistinctViewers(eq("ch-1"), any(), any())).thenReturn(3L);
        when(channelViewLogMapper.selectActiveUserIds(eq("ch-1"), any(), any()))
                .thenReturn(List.of("u1", "u2"));
        when(channelStatsMapper.countPostsBetween(eq("ch-1"), any(), any())).thenReturn(4L);
        when(channelStatsMapper.countCommentsBetween(eq("ch-1"), any(), any())).thenReturn(5L);
        when(channelStatsMapper.countPostLikesBetween(eq("ch-1"), any(), any())).thenReturn(4L);
        when(channelStatsMapper.countCommentLikesBetween(eq("ch-1"), any(), any())).thenReturn(2L);
        when(channelStatsMapper.countSharesBetween(eq("ch-1"), any(), any())).thenReturn(1L);
        when(dailyChannelStatsMapper.selectByChannelAndDate("ch-1", date)).thenReturn(null);
        when(dailyChannelStatsMapper.upsert(any(DailyChannelStats.class))).thenReturn(1);

        DailyStatsResponse response = service.aggregate("ch-1", date);

        assertThat(response.statDate()).isEqualTo(date);
        assertThat(response.memberCount()).isEqualTo(10);
        assertThat(response.newMembers()).isEqualTo(2);
        assertThat(response.lostMembers()).isEqualTo(1);
        assertThat(response.visitUv()).isEqualTo(3);
        assertThat(response.postViews()).isEqualTo(5);
        assertThat(response.activeUsers()).isEqualTo(2);
        assertThat(response.activeRate()).isEqualByComparingTo("0.2000");
        assertThat(response.posts()).isEqualTo(4);
        assertThat(response.comments()).isEqualTo(5);
        assertThat(response.likes()).isEqualTo(6);
        assertThat(response.shares()).isEqualTo(1);
        assertThat(response.interactions()).isEqualTo(12);
        assertThat(response.interactionRate()).isEqualByComparingTo("2.4000");

        ArgumentCaptor<DailyChannelStats> captor = ArgumentCaptor.forClass(DailyChannelStats.class);
        verify(dailyChannelStatsMapper).upsert(captor.capture());
        assertThat(captor.getValue().getChannelId()).isEqualTo("ch-1");
        assertThat(captor.getValue().getStatDate()).isEqualTo(date);
    }

    @Test
    void should_reuse_existing_row_when_aggregating_same_day_again() {
        LocalDate date = LocalDate.of(2026, 11, 13);
        DailyChannelStats existing = new DailyChannelStats();
        existing.setId("existing-id");
        existing.setChannelId("ch-1");
        existing.setStatDate(date);
        existing.setCreatedAt(LocalDateTime.of(2026, 11, 14, 0, 10));
        when(dailyChannelStatsMapper.selectByChannelAndDate("ch-1", date)).thenReturn(existing);
        when(channelStatsMapper.countActiveMembers("ch-1")).thenReturn(10L);
        when(channelViewLogMapper.selectActiveUserIds(eq("ch-1"), any(), any())).thenReturn(List.of());
        when(dailyChannelStatsMapper.upsert(any(DailyChannelStats.class))).thenReturn(1);

        service.aggregate("ch-1", date);

        ArgumentCaptor<DailyChannelStats> captor = ArgumentCaptor.forClass(DailyChannelStats.class);
        verify(dailyChannelStatsMapper).upsert(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("existing-id");
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 14, 0, 10));
    }

    @Test
    void should_reject_overview_without_data_view_permission() {
        when(accessService.requirePermission("ch-1", "user-2", com.isles.channel.service.ChannelPermissions.DATA_VIEW))
                .thenThrow(new AppException(ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> service.overview("ch-1", "user-2"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(dailyChannelStatsMapper, never()).selectByChannelAndDate(anyString(), any(LocalDate.class));
    }
}
