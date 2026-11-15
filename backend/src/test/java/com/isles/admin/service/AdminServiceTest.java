package com.isles.admin.service;

import com.isles.admin.dto.AdminDtos;
import com.isles.admin.dto.AdminUserRow;
import com.isles.admin.mapper.AdminChannelMapper;
import com.isles.admin.mapper.AdminReportMapper;
import com.isles.admin.mapper.AdminStatsMapper;
import com.isles.admin.mapper.AdminUserMapper;
import com.isles.ai.service.AiConversationService;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.comment.mapper.CommentMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.security.CurrentUserProvider;
import com.isles.post.mapper.PostMapper;
import com.isles.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台后台服务单元测试：概览聚合、举报表未就绪降级、用户禁用治理（不能禁用自己）。
 */
class AdminServiceTest {

    private AdminUserMapper adminUserMapper;
    private AdminChannelMapper adminChannelMapper;
    private AdminReportMapper adminReportMapper;
    private AdminStatsMapper adminStatsMapper;
    private UserMapper userMapper;
    private ChannelMapper channelMapper;
    private PostMapper postMapper;
    private CommentMapper commentMapper;
    private AiConversationService aiConversationService;
    private CurrentUserProvider currentUserProvider;
    private AdminService service;

    @BeforeEach
    void setUp() {
        adminUserMapper = mock(AdminUserMapper.class);
        adminChannelMapper = mock(AdminChannelMapper.class);
        adminReportMapper = mock(AdminReportMapper.class);
        adminStatsMapper = mock(AdminStatsMapper.class);
        userMapper = mock(UserMapper.class);
        channelMapper = mock(ChannelMapper.class);
        postMapper = mock(PostMapper.class);
        commentMapper = mock(CommentMapper.class);
        aiConversationService = mock(AiConversationService.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        service = new AdminService(adminUserMapper, adminChannelMapper, adminReportMapper, adminStatsMapper,
                userMapper, channelMapper, postMapper, commentMapper, aiConversationService, currentUserProvider);
    }

    @Test
    void should_aggregate_platform_overview() {
        when(userMapper.selectCount(any())).thenReturn(100L);
        when(channelMapper.selectCount(any())).thenReturn(12L);
        when(postMapper.selectCount(any())).thenReturn(340L);
        when(commentMapper.selectCount(any())).thenReturn(880L);
        when(aiConversationService.countConversations()).thenReturn(9L);
        when(aiConversationService.countMessages()).thenReturn(27L);
        when(adminStatsMapper.countUsersSince(any())).thenReturn(2L);
        when(adminStatsMapper.countPostsSince(any())).thenReturn(5L);
        when(adminStatsMapper.countActiveUsersSince(any())).thenReturn(42L);
        when(adminReportMapper.countReportsTable()).thenReturn(0L);

        AdminDtos.PlatformOverviewResponse overview = service.overview();

        assertThat(overview.userCount()).isEqualTo(100L);
        assertThat(overview.channelCount()).isEqualTo(12L);
        assertThat(overview.postCount()).isEqualTo(340L);
        assertThat(overview.commentCount()).isEqualTo(880L);
        assertThat(overview.todayNewUsers()).isEqualTo(2L);
        assertThat(overview.todayNewPosts()).isEqualTo(5L);
        assertThat(overview.todayActiveUsers()).isEqualTo(42L);
        assertThat(overview.aiConversationCount()).isEqualTo(9L);
        assertThat(overview.aiMessageCount()).isEqualTo(27L);
        assertThat(overview.reportTableReady()).isFalse();
        assertThat(overview.pendingReportCount()).isZero();
    }

    @Test
    void should_return_unavailable_reports_when_table_missing() {
        when(adminReportMapper.countReportsTable()).thenReturn(0L);

        AdminDtos.ReportPage page = service.listReports("PENDING", 1, 20);

        assertThat(page.available()).isFalse();
        assertThat(page.items()).isEmpty();
        assertThat(page.dependency()).contains("reports");
    }

    @Test
    void should_reject_report_handling_when_table_missing() {
        when(adminReportMapper.countReportsTable()).thenReturn(0L);

        assertThatThrownBy(() -> service.handleReport("report-1", "DISMISS", null))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));
    }

    @Test
    void should_reject_banning_self() {
        when(currentUserProvider.requireUserId()).thenReturn("admin-1");

        assertThatThrownBy(() -> service.banUser("admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));
    }

    @Test
    void should_ban_user_and_update_status() {
        AdminUserRow row = new AdminUserRow();
        row.setId("user-9");
        row.setUsername("spam");
        row.setStatus("ACTIVE");
        row.setPlatformRole("USER");
        row.setCreatedAt(LocalDateTime.now());
        when(adminUserMapper.selectRowById("user-9")).thenReturn(row);
        when(adminUserMapper.updateStatus("user-9", "BANNED")).thenReturn(1);

        AdminDtos.AdminUserResponse response = service.banUser("user-9");

        assertThat(response.status()).isEqualTo("BANNED");
        verify(adminUserMapper).updateStatus("user-9", "BANNED");
    }

    @Test
    void should_page_users_with_keyword() {
        when(adminUserMapper.selectPage(anyString(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(java.util.List.of());
        when(adminUserMapper.countPage(anyString(), any())).thenReturn(0L);

        AdminDtos.AdminUserPage page = service.listUsers("  spam  ", null, 1, 20);

        assertThat(page.items()).isEmpty();
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(20);
    }
}
