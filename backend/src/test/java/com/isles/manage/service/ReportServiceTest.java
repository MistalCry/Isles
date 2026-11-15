package com.isles.manage.service;

import com.isles.channel.audit.AuditLogService;
import com.isles.channel.entity.Channel;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.comment.mapper.CommentMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.manage.dto.HandleReportRequest;
import com.isles.manage.dto.ReportCreateRequest;
import com.isles.manage.dto.ReportResponse;
import com.isles.manage.entity.Report;
import com.isles.channel.mapper.ReportMapper;
import com.isles.notification.service.NotificationService;
import com.isles.post.entity.Post;
import com.isles.post.mapper.PostMapper;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 举报状态机单测：PENDING → ACCEPTED/REJECTED，重复处理拒绝、处置动作与通知。
 */
class ReportServiceTest {

    private ReportMapper reportMapper;
    private PostMapper postMapper;
    private CommentMapper commentMapper;
    private ChannelMapper channelMapper;
    private UserMapper userMapper;
    private ChannelAccessService accessService;
    private ContentManageService contentManageService;
    private MemberManageService memberManageService;
    private NotificationService notificationService;
    private AuditLogService auditLogService;
    private ReportService service;

    @BeforeEach
    void setUp() {
        reportMapper = mock(ReportMapper.class);
        postMapper = mock(PostMapper.class);
        commentMapper = mock(CommentMapper.class);
        channelMapper = mock(ChannelMapper.class);
        userMapper = mock(UserMapper.class);
        accessService = mock(ChannelAccessService.class);
        contentManageService = mock(ContentManageService.class);
        memberManageService = mock(MemberManageService.class);
        notificationService = mock(NotificationService.class);
        auditLogService = mock(AuditLogService.class);
        service = new ReportService(reportMapper, postMapper, commentMapper, channelMapper, userMapper,
                accessService, contentManageService, memberManageService, notificationService, auditLogService);
    }

    @Test
    void should_reject_duplicate_pending_report_by_same_reporter() {
        when(postMapper.selectById("post-1")).thenReturn(post());
        when(accessService.requireVisibleChannel("ch-1", "user-9")).thenReturn(channel());
        when(reportMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(
                new ReportCreateRequest("POST", "post-1", "SPAM", "重复内容"), "user-9"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.REPORT_ALREADY_EXISTS));
        verify(reportMapper, never()).insert(any(Report.class));
    }

    @Test
    void should_create_pending_report_for_visible_post() {
        when(postMapper.selectById("post-1")).thenReturn(post());
        when(accessService.requireVisibleChannel("ch-1", "user-9")).thenReturn(channel());
        when(reportMapper.selectCount(any())).thenReturn(0L);
        when(reportMapper.insert(any(Report.class))).thenReturn(1);
        when(postMapper.selectBatchIds(any())).thenReturn(List.of(post()));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user("user-9", "举报人")));
        when(channelMapper.selectBatchIds(any())).thenReturn(List.of(channel()));

        ReportResponse response = service.create(
                new ReportCreateRequest("POST", "post-1", "SPAM", "重复内容"), "user-9");

        assertThat(response.status()).isEqualTo(Report.Status.PENDING.name());
        assertThat(response.reporterId()).isEqualTo("user-9");
        assertThat(response.targetId()).isEqualTo("post-1");
        verify(reportMapper).insert(any(Report.class));
        verify(auditLogService).record(anyString(), anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void should_reject_handling_already_handled_report() {
        Report report = pendingReport();
        report.setStatus(Report.Status.ACCEPTED.name());
        when(accessService.requirePermission("ch-1", "admin-1", ChannelPermissions.CONTENT_MANAGE))
                .thenReturn(context());
        when(reportMapper.selectById("report-1")).thenReturn(report);

        assertThatThrownBy(() -> service.handle("ch-1", "report-1",
                new HandleReportRequest("ACCEPTED", "NONE", null, null), "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.REPORT_ALREADY_HANDLED));
    }

    @Test
    void should_accept_report_and_delete_target_content() {
        when(accessService.requirePermission(anyString(), anyString(), anyString())).thenReturn(context());
        when(reportMapper.selectById("report-1")).thenReturn(pendingReport());
        when(postMapper.selectById("post-1")).thenReturn(post());
        when(reportMapper.updateById(any(Report.class))).thenReturn(1);
        when(postMapper.selectBatchIds(any())).thenReturn(List.of(post()));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user("user-9", "举报人")));
        when(channelMapper.selectBatchIds(any())).thenReturn(List.of(channel()));

        ReportResponse response = service.handle("ch-1", "report-1",
                new HandleReportRequest("ACCEPTED", "DELETE_CONTENT", "违规内容", null), "admin-1");

        assertThat(response.status()).isEqualTo(Report.Status.ACCEPTED.name());
        assertThat(response.handleAction()).isEqualTo(Report.HandleAction.DELETE_CONTENT.name());
        verify(contentManageService).deletePost("ch-1", "post-1", "admin-1");
        verify(notificationService).notifySystem(eq("user-9"), any(), eq("ch-1"), any(), any(),
                anyString(), anyString(), anyMap());
        verify(auditLogService).record(anyString(), anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void should_reject_rejected_status_with_disposal_action() {
        when(accessService.requirePermission(anyString(), anyString(), anyString())).thenReturn(context());
        when(reportMapper.selectById("report-1")).thenReturn(pendingReport());

        assertThatThrownBy(() -> service.handle("ch-1", "report-1",
                new HandleReportRequest("REJECTED", "DELETE_CONTENT", null, null), "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(contentManageService, never()).deletePost(anyString(), anyString(), anyString());
    }

    @Test
    void should_mute_author_when_accept_action_is_mute() {
        when(accessService.requirePermission(anyString(), anyString(), anyString())).thenReturn(context());
        when(reportMapper.selectById("report-1")).thenReturn(pendingReport());
        when(postMapper.selectById("post-1")).thenReturn(post());
        when(reportMapper.updateById(any(Report.class))).thenReturn(1);
        when(postMapper.selectBatchIds(any())).thenReturn(List.of(post()));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user("user-9", "举报人")));
        when(channelMapper.selectBatchIds(any())).thenReturn(List.of(channel()));

        ReportResponse response = service.handle("ch-1", "report-1",
                new HandleReportRequest("ACCEPTED", "MUTE_AUTHOR", "禁言处理", 3600L), "admin-1");

        assertThat(response.status()).isEqualTo(Report.Status.ACCEPTED.name());
        assertThat(response.handleAction()).isEqualTo(Report.HandleAction.MUTE_AUTHOR.name());
        verify(memberManageService).mute(eq("ch-1"), eq("author-1"), any(), eq("admin-1"));
        verify(contentManageService, never()).deletePost(anyString(), anyString(), anyString());
    }

    private ChannelAccessService.ChannelAccessContext context() {
        Channel channel = channel();
        return new ChannelAccessService.ChannelAccessContext(channel, null, null);
    }

    private Channel channel() {
        Channel channel = new Channel();
        channel.setId("ch-1");
        channel.setName("VRChat 中文社区");
        channel.setOwnerId("owner-1");
        channel.setStatus(Channel.Status.ACTIVE.name());
        return channel;
    }

    private Post post() {
        Post post = new Post();
        post.setId("post-1");
        post.setChannelId("ch-1");
        post.setBoardId("board-1");
        post.setAuthorId("author-1");
        post.setTitle("违规标题");
        post.setContent("违规内容");
        post.setContentFormat(Post.ContentFormat.PLAIN.name());
        post.setViewCount(0L);
        post.setLikeCount(0L);
        post.setCommentCount(0L);
        post.setShareCount(0L);
        post.setIsPinned(false);
        post.setIsEssence(false);
        post.setStatus(Post.Status.PUBLISHED.name());
        post.setCreatedAt(LocalDateTime.now());
        return post;
    }

    private Report pendingReport() {
        Report report = new Report();
        report.setId("report-1");
        report.setReporterId("user-9");
        report.setChannelId("ch-1");
        report.setTargetType(Report.TargetType.POST.name());
        report.setTargetId("post-1");
        report.setReason("SPAM");
        report.setStatus(Report.Status.PENDING.name());
        report.setCreatedAt(LocalDateTime.now().minusHours(1));
        return report;
    }

    private User user(String id, String nickname) {
        User user = new User();
        user.setId(id);
        user.setUsername("user-" + id);
        user.setNickname(nickname);
        user.setStatus(User.Status.ACTIVE.name());
        return user;
    }
}
