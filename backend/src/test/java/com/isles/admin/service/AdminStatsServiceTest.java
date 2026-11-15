package com.isles.admin.service;

import com.isles.admin.dto.AdminStatsDtos;
import com.isles.admin.dto.AdminTrendRow;
import com.isles.admin.mapper.AdminTrendMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.platform.audit.PlatformAuditService;
import com.isles.search.entity.HotKeyword;
import com.isles.search.mapper.HotKeywordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台趋势与热词单元测试（docs/11 §5.5）：缺失日期补零、窗口上限、热词下线。
 */
class AdminStatsServiceTest {

    private AdminTrendMapper trendMapper;
    private HotKeywordMapper hotKeywordMapper;
    private AdminStatsService service;

    @BeforeEach
    void setUp() {
        trendMapper = mock(AdminTrendMapper.class);
        hotKeywordMapper = mock(HotKeywordMapper.class);
        service = new AdminStatsService(trendMapper, hotKeywordMapper, mock(PlatformAuditService.class));
        when(trendMapper.countUsersByDay(any())).thenReturn(List.of());
        when(trendMapper.countPostsByDay(any())).thenReturn(List.of());
        when(trendMapper.countCommentsByDay(any())).thenReturn(List.of());
        when(trendMapper.countActiveUsersByDay(any())).thenReturn(List.of());
    }

    @Test
    void should_fill_missing_days_with_zero() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(trendMapper.countUsersByDay(any())).thenReturn(List.of(row(today, 3L)));

        AdminStatsDtos.TrendResponse response = service.trend(7);

        assertThat(response.points()).hasSize(7);
        AdminStatsDtos.TrendPoint last = response.points().get(6);
        assertThat(last.date()).isEqualTo(today.toString());
        assertThat(last.newUsers()).isEqualTo(3L);
        assertThat(response.points().get(0).newUsers()).isZero();
    }

    @Test
    void should_clamp_days_to_maximum() {
        AdminStatsDtos.TrendResponse response = service.trend(500);

        assertThat(response.points()).hasSize(90);
    }

    @Test
    void should_default_to_thirty_days() {
        assertThat(service.trend(null).points()).hasSize(30);
    }

    @Test
    void should_reject_deleting_missing_hot_keyword() {
        when(hotKeywordMapper.selectById("ghost")).thenReturn(null);

        assertThatThrownBy(() -> service.deleteHotKeyword("ghost", "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void should_delete_hot_keyword() {
        HotKeyword keyword = new HotKeyword();
        keyword.setId("hk-1");
        keyword.setKeyword("刷量词");
        when(hotKeywordMapper.selectById("hk-1")).thenReturn(keyword);

        service.deleteHotKeyword("hk-1", "admin-1");

        verify(hotKeywordMapper).deleteById("hk-1");
    }

    private AdminTrendRow row(LocalDate day, Long total) {
        AdminTrendRow row = new AdminTrendRow();
        row.setDay(day);
        row.setTotal(total);
        return row;
    }
}
