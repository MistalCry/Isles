package com.isles.admin.service;

import com.isles.admin.dto.AdminContentRow;
import com.isles.admin.dto.AdminDtos;
import com.isles.admin.dto.AdminUserRow;
import com.isles.admin.mapper.AdminChannelMapper;
import com.isles.admin.mapper.AdminContentMapper;
import com.isles.admin.mapper.AdminReportMapper;
import com.isles.admin.mapper.AdminStatsMapper;
import com.isles.admin.mapper.AdminUserMapper;
import com.isles.ai.service.AiConversationService;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.comment.mapper.CommentMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.security.CurrentUserProvider;
import com.isles.manage.entity.Report;
import com.isles.notification.service.NotificationService;
import com.isles.platform.audit.PlatformAuditService;
import com.isles.platform.penalty.PenaltyDtos;
import com.isles.platform.penalty.PenaltyService;
import com.isles.post.mapper.PostMapper;
import com.isles.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台后台服务单元测试（docs/11 §5）：概览聚合、举报处置状态与处理人、封禁走处罚记录。
 */
class AdminServiceTest {

    private AdminUserMapper adminUserMapper;
    private AdminChannelMapper adminChannelMapper;
    private AdminReportMapper adminReportMapper;
    private AdminContentMapper adminContentMapper;
    private AdminStatsMapper adminStatsMapper;
    private UserMapper userMapper;
    private ChannelMapper channelMapper;
    private PostMapper postMapper;
    private CommentMapper commentMapper;
    private AiConversationService aiConversationService;
    private CurrentUserProvider currentUserProvider;
    private PenaltyService penaltyService;
    private AdminModerationService moderationService;
    private NotificationService notificationService;
    private PlatformAuditService auditService;
    private AdminService service;

    @BeforeEach
    void setUp() {
        adminUserMapper = mock(AdminUserMapper.class);
        adminChannelMapper = mock(AdminChannelMapper.class);
        adminReportMapper = mock(AdminReportMapper.class);
        adminContentMapper = mock(AdminContentMapper.class);
        adminStatsMapper = mock(AdminStatsMapper.class);
        userMapper = mock(UserMapper.class);
        channelMapper = mock(ChannelMapper.class);
        postMapper = mock(PostMapper.class);
        commentMapper = mock(CommentMapper.class);
        aiConversationService = mock(AiConversationService.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        penaltyService = mock(PenaltyService.class);
        moderationService = mock(AdminModerationService.class);
        notificationService = mock(NotificationService.class);
        auditService = mock(PlatformAuditService.class);
        service = new AdminService(adminUserMapper, adminChannelMapper, adminReportMapper, adminContentMapper,
                adminStatsMapper,
                userMapper, channelMapper, postMapper, commentMapper, aiConversationService, currentUserProvider,
                penaltyService, moderationService, notificationService, auditService);
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
        when(adminReportMapper.countPage("PENDING", null, null)).thenReturn(3L);

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
        assertThat(overview.reportTableReady()).isTrue();
        assertThat(overview.pendingReportCount()).isEqualTo(3L);
    }

    @Test
    void should_map_reports_with_unified_status() {
        when(adminReportMapper.countPage("PENDING", null, null)).thenReturn(1L);
        when(adminReportMapper.selectPage("PENDING", null, null, 20, 0)).thenReturn(List.of(report("PENDING")));

        AdminDtos.ReportPage page = service.listReports("PENDING", null, null, 1, 20);

        assertThat(page.available()).isTrue();
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).status()).isEqualTo("PENDING");
        assertThat(page.items().get(0).channelId()).isEqualTo("ch-1");
    }

    @Test
    void should_write_accepted_status_and_handler_when_resolving() {
        when(currentUserProvider.requireUserId()).thenReturn("admin-1");
        when(adminReportMapper.selectById("report-1")).thenReturn(report("PENDING"));
        when(adminReportMapper.handle("report-1", "ACCEPTED", "admin-1", "NONE", "已核实"))
                .thenReturn(1);

        AdminDtos.HandleReportResponse response = service.handleReport("report-1",
                new AdminDtos.HandleReportRequest("RESOLVE", "已核实", null));

        assertThat(response.status()).isEqualTo("ACCEPTED");
        verify(adminReportMapper).handle("report-1", "ACCEPTED", "admin-1", "NONE", "已核实");
        verify(notificationService).notifySystem(eq("reporter-1"), any(), eq("ch-1"), eq("post-1"), eq(null),
                eq("举报已处理"), any(), eq(null));
        verify(auditService).record(eq("admin-1"), eq(null), eq("platform.report.handle"), eq("REPORT"),
                eq("report-1"), any());
    }

    @Test
    void should_write_rejected_status_when_dismissing() {
        when(currentUserProvider.requireUserId()).thenReturn("admin-1");
        when(adminReportMapper.selectById("report-1")).thenReturn(report("PENDING"));
        when(adminReportMapper.handle("report-1", "REJECTED", "admin-1", "NONE", null)).thenReturn(1);

        AdminDtos.HandleReportResponse response = service.handleReport("report-1",
                new AdminDtos.HandleReportRequest("DISMISS", null, "DELETE_CONTENT"));

        // 驳回时忽略处置动作，避免误下架
        assertThat(response.status()).isEqualTo("REJECTED");
        verify(adminReportMapper).handle("report-1", "REJECTED", "admin-1", "NONE", null);
    }

    @Test
    void should_reject_handling_already_handled_report() {
        when(currentUserProvider.requireUserId()).thenReturn("admin-1");
        when(adminReportMapper.selectById("report-1")).thenReturn(report("ACCEPTED"));

        assertThatThrownBy(() -> service.handleReport("report-1",
                new AdminDtos.HandleReportRequest("RESOLVE", null, null)))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.REPORT_ALREADY_HANDLED));
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
    void should_ban_user_through_penalty_record() {
        AdminUserRow row = new AdminUserRow();
        row.setId("user-9");
        row.setUsername("spam");
        row.setStatus("ACTIVE");
        row.setPlatformRole("USER");
        row.setCreatedAt(LocalDateTime.now());
        when(currentUserProvider.requireUserId()).thenReturn("admin-1");
        when(adminUserMapper.selectRowById("user-9")).thenReturn(row);
        when(penaltyService.findActive("user-9", "BAN")).thenReturn(null);

        AdminDtos.AdminUserResponse response = service.banUser("user-9");

        assertThat(response.status()).isEqualTo("BANNED");
        verify(penaltyService).create(eq("user-9"), any(PenaltyDtos.CreatePenaltyRequest.class), eq("admin-1"));
    }

    @Test
    void should_page_users_with_keyword() {
        when(adminUserMapper.selectPage(anyString(), any(), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of());
        when(adminUserMapper.countPage(anyString(), any())).thenReturn(0L);

        AdminDtos.AdminUserPage page = service.listUsers("  spam  ", null, 1, 20);

        assertThat(page.items()).isEmpty();
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(20);
    }


    /** 评论类举报 → 删除内容：走评论下架，且通知里带评论摘要（不能 500 / 不能只说「举报已处理」）。 */
    @Test
    void should_takedown_comment_and_notify_with_excerpt() {
        Report commentReport = report("PENDING");
        commentReport.setTargetType("COMMENT");
        commentReport.setTargetId("comment-1");
        when(currentUserProvider.requireUserId()).thenReturn("admin-1");
        when(adminReportMapper.selectById("report-1")).thenReturn(commentReport);
        when(adminContentMapper.selectComment("comment-1"))
                .thenReturn(contentRow("COMMENT", null, "这条评论包含大量广告与无关链接，请处理"));
        when(adminReportMapper.handle("report-1", "ACCEPTED", "admin-1", "DELETE_CONTENT", null)).thenReturn(1);

        service.handleReport("report-1", new AdminDtos.HandleReportRequest("RESOLVE", null, "DELETE_CONTENT"));

        verify(moderationService).takedownComment("comment-1", "举报核实：SPAM", "admin-1");
        verify(moderationService, org.mockito.Mockito.never()).takedownPost(anyString(), anyString(), anyString());
        org.mockito.ArgumentCaptor<String> content = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notificationService).notifySystem(eq("reporter-1"), any(), eq("ch-1"), eq(null), eq("comment-1"),
                eq("举报已处理"), content.capture(), eq(null));
        assertThat(content.getValue()).startsWith("你举报的评论（这条评论包含大量广告").contains("已被删除");
    }

    /** 帖子类举报 → 删除内容：通知带帖子标题。 */
    @Test
    void should_notify_post_title_when_takedown() {
        when(currentUserProvider.requireUserId()).thenReturn("admin-1");
        when(adminReportMapper.selectById("report-1")).thenReturn(report("PENDING"));
        when(adminContentMapper.selectPost("post-1"))
                .thenReturn(contentRow("POST", "露营装备清单", "正文摘要"));
        when(adminReportMapper.handle("report-1", "ACCEPTED", "admin-1", "DELETE_CONTENT", null)).thenReturn(1);

        service.handleReport("report-1", new AdminDtos.HandleReportRequest("RESOLVE", null, "DELETE_CONTENT"));

        verify(moderationService).takedownPost("post-1", "举报核实：SPAM", "admin-1");
        org.mockito.ArgumentCaptor<String> content = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notificationService).notifySystem(any(), any(), any(), any(), any(), any(), content.capture(), any());
        assertThat(content.getValue()).isEqualTo("你举报的帖子《露营装备清单》已被删除");
    }

    /** 驳回：文案说明「经核查未违规」。 */
    @Test
    void should_notify_rejected_with_verdict() {
        when(currentUserProvider.requireUserId()).thenReturn("admin-1");
        when(adminReportMapper.selectById("report-1")).thenReturn(report("PENDING"));
        when(adminReportMapper.handle("report-1", "REJECTED", "admin-1", "NONE", null)).thenReturn(1);

        service.handleReport("report-1", new AdminDtos.HandleReportRequest("DISMISS", null, null));

        org.mockito.ArgumentCaptor<String> content = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notificationService).notifySystem(any(), any(), any(), any(), any(), eq("举报未通过"),
                content.capture(), any());
        assertThat(content.getValue()).isEqualTo("你举报的内容经核查未违规");
    }

    /** 快照查不到时（内容已被物理删除等）仍能给出可读文案，不抛异常。 */
    @Test
    void should_fallback_when_target_snapshot_missing() {
        when(currentUserProvider.requireUserId()).thenReturn("admin-1");
        when(adminReportMapper.selectById("report-1")).thenReturn(report("PENDING"));
        when(adminContentMapper.selectPost("post-1")).thenReturn(null);
        when(adminReportMapper.handle("report-1", "ACCEPTED", "admin-1", "NONE", null)).thenReturn(1);

        service.handleReport("report-1", new AdminDtos.HandleReportRequest("RESOLVE", null, null));

        org.mockito.ArgumentCaptor<String> content = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notificationService).notifySystem(any(), any(), any(), any(), any(), any(), content.capture(), any());
        assertThat(content.getValue()).isEqualTo("你举报的帖子已处理");
    }

    private Report report(String status) {
        Report report = new Report();
        report.setId("report-1");
        report.setReporterId("reporter-1");
        report.setChannelId("ch-1");
        report.setTargetType("POST");
        report.setTargetId("post-1");
        report.setReason("SPAM");
        report.setStatus(status);
        report.setCreatedAt(LocalDateTime.now());
        return report;
    }

    private AdminContentRow contentRow(String targetType, String title, String preview) {
        AdminContentRow row = new AdminContentRow();
        row.setId("target-1");
        row.setTargetType(targetType);
        row.setChannelId("ch-1");
        row.setTitle(title);
        row.setContentPreview(preview);
        row.setStatus("PUBLISHED");
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }
}
