package com.isles.ai.service;

import com.isles.ai.config.AiProperties;
import com.isles.ai.dto.AiContextPost;
import com.isles.ai.dto.AiResponses;
import com.isles.ai.entity.AiModelConfig;
import com.isles.ai.entity.AiTaskLog;
import com.isles.ai.entity.ChannelAiSetting;
import com.isles.ai.mapper.AiTaskLogMapper;
import com.isles.ai.mapper.ChannelAiSettingMapper;
import com.isles.ai.provider.AiProvider;
import com.isles.ai.provider.AiProviderException;
import com.isles.channel.entity.Channel;
import com.isles.channel.mapper.BoardMapper;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.comment.mapper.CommentMapper;
import com.isles.comment.service.CommentService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.interaction.service.InteractionService;
import com.isles.post.service.PostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * 频道助手任务单元测试（docs/03 FR-ASSIST-03/04/05/06）：
 * 未配置助手报错、未配置模型跳过、每日频率上限、自动点赞落库、幂等键重复执行。
 */
class AiTaskServiceTest {

    private ChannelAiSettingMapper settingMapper;
    private AiTaskLogMapper taskLogMapper;
    private ChannelAiSettingsService settingsService;
    private AiContextService contextService;
    private AiModelConfigService modelConfigService;
    private PostService postService;
    private CommentService commentService;
    private InteractionService interactionService;
    private BoardMapper boardMapper;
    private ChannelMapper channelMapper;
    private CommentMapper commentMapper;
    private AiTaskService service;

    @BeforeEach
    void setUp() {
        settingMapper = mock(ChannelAiSettingMapper.class);
        taskLogMapper = mock(AiTaskLogMapper.class);
        settingsService = mock(ChannelAiSettingsService.class);
        contextService = mock(AiContextService.class);
        modelConfigService = mock(AiModelConfigService.class);
        postService = mock(PostService.class);
        commentService = mock(CommentService.class);
        interactionService = mock(InteractionService.class);
        boardMapper = mock(BoardMapper.class);
        channelMapper = mock(ChannelMapper.class);
        commentMapper = mock(CommentMapper.class);
        service = new AiTaskService(settingMapper, taskLogMapper, settingsService, contextService,
                modelConfigService, postService, commentService, interactionService, boardMapper, channelMapper,
                commentMapper, AiProperties.defaults());
    }

    @Test
    void should_reject_run_when_assistant_setting_missing() {
        when(settingsService.findSetting("ch-1")).thenReturn(null);

        assertThatThrownBy(() -> service.runTask("ch-1", "AUTO_POST"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION));
    }

    @Test
    void should_log_skipped_when_model_not_configured() {
        ChannelAiSetting setting = setting("AUTO_POST", 1);
        when(settingsService.findSetting("ch-1")).thenReturn(setting);
        when(taskLogMapper.selectByKey(anyString(), anyString(), anyString())).thenReturn(null);
        when(taskLogMapper.countSuccessBetween(anyString(), anyString(), any(), any())).thenReturn(0L);
        when(taskLogMapper.insert(any(AiTaskLog.class))).thenReturn(1);
        when(taskLogMapper.updateById(any(AiTaskLog.class))).thenReturn(1);
        when(modelConfigService.resolveDefault())
                .thenThrow(new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "未配置 AI 模型"));

        AiResponses.TaskLogResponse response = service.runTask("ch-1", "AUTO_POST");

        assertThat(response.status()).isEqualTo(AiTaskLog.Status.SKIPPED.name());
        assertThat(response.resultText()).contains("未配置 AI 模型");
        ArgumentCaptor<AiTaskLog> captor = ArgumentCaptor.forClass(AiTaskLog.class);
        verify(taskLogMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AiTaskLog.Status.SKIPPED.name());
        assertThat(captor.getValue().getFinishedAt()).isNotNull();
    }

    @Test
    void should_skip_when_daily_frequency_limit_reached() {
        ChannelAiSetting setting = setting("AUTO_POST", 1);
        when(settingsService.findSetting("ch-1")).thenReturn(setting);
        when(modelConfigService.resolveDefault()).thenReturn(modelConfig());
        when(taskLogMapper.selectByKey(anyString(), anyString(), anyString())).thenReturn(null);
        when(taskLogMapper.countSuccessBetween(anyString(), anyString(), any(), any())).thenReturn(1L);
        when(taskLogMapper.insert(any(AiTaskLog.class))).thenReturn(1);
        when(taskLogMapper.updateById(any(AiTaskLog.class))).thenReturn(1);

        AiResponses.TaskLogResponse response = service.runTask("ch-1", "AUTO_POST");

        assertThat(response.status()).isEqualTo(AiTaskLog.Status.SKIPPED.name());
        assertThat(response.resultText()).contains("每日执行频率上限");
    }

    @Test
    void should_like_recent_post_with_assistant_account() {
        ChannelAiSetting setting = setting("AUTO_LIKE", 10);
        when(settingsService.findSetting("ch-1")).thenReturn(setting);
        when(modelConfigService.resolveDefault()).thenReturn(modelConfig());
        when(taskLogMapper.selectByKey(anyString(), anyString(), anyString())).thenReturn(null);
        when(taskLogMapper.countSuccessBetween(anyString(), anyString(), any(), any())).thenReturn(0L);
        when(taskLogMapper.insert(any(AiTaskLog.class))).thenReturn(1);
        when(taskLogMapper.updateById(any(AiTaskLog.class))).thenReturn(1);
        when(settingsService.ensureAssistantAccountInternal("ch-1")).thenReturn("assistant-1");
        AiContextPost post = new AiContextPost();
        post.setId("post-1");
        post.setAuthorId("user-2");
        post.setTitle("求助帖");
        post.setContent("有人一起玩吗");
        when(contextService.recentChannelPosts(eq("ch-1"), any(), any(Integer.class))).thenReturn(List.of(post));

        AiResponses.TaskLogResponse response = service.runTask("ch-1", "AUTO_LIKE");

        assertThat(response.status()).isEqualTo(AiTaskLog.Status.SUCCESS.name());
        assertThat(response.resultText()).contains("已自动点赞");
        verify(interactionService).likePost("post-1", "assistant-1");
    }

    @Test
    void should_return_existing_log_for_idempotent_task() {
        ChannelAiSetting setting = setting("DAILY_SUMMARY", 1);
        AiTaskLog existing = new AiTaskLog();
        existing.setId("log-1");
        existing.setChannelId("ch-1");
        existing.setTaskType("DAILY_SUMMARY");
        existing.setStatus(AiTaskLog.Status.SUCCESS.name());
        when(settingsService.findSetting("ch-1")).thenReturn(setting);
        when(taskLogMapper.selectByKey(anyString(), anyString(), anyString())).thenReturn(existing);

        AiResponses.TaskLogResponse response = service.runTask("ch-1", "DAILY_SUMMARY");

        assertThat(response.id()).isEqualTo("log-1");
        verify(taskLogMapper, never()).insert(any(AiTaskLog.class));
    }


    @Test
    void should_use_channel_model_when_configured() {
        ChannelAiSetting setting = setting("AUTO_POST", 1);
        setting.setModelConfigId("cfg-ch");
        when(settingsService.findSetting("ch-1")).thenReturn(setting);
        when(taskLogMapper.selectByKey(anyString(), anyString(), anyString())).thenReturn(null);
        when(taskLogMapper.countSuccessBetween(anyString(), anyString(), any(), any())).thenReturn(0L);
        when(taskLogMapper.insert(any(AiTaskLog.class))).thenReturn(1);
        when(taskLogMapper.updateById(any(AiTaskLog.class))).thenReturn(1);
        when(settingsService.ensureAssistantAccountInternal("ch-1")).thenReturn("assistant-1");
        when(contextService.recentChannelPosts(eq("ch-1"), any(), any(Integer.class))).thenReturn(List.of(post()));
        when(channelMapper.selectById("ch-1")).thenReturn(channel());

        AiModelConfig channelModel = modelConfig();
        channelModel.setId("cfg-ch");
        channelModel.setModel("channel-model");
        AiModelConfig defaultModel = modelConfig();
        when(modelConfigService.resolveDefault()).thenReturn(defaultModel);
        when(modelConfigService.findEnabled("cfg-ch")).thenReturn(channelModel);
        AiProvider provider = mock(AiProvider.class);
        when(modelConfigService.providerFor(channelModel)).thenReturn(provider);
        when(provider.chat(any())).thenThrow(new AiProviderException(AiProviderException.UPSTREAM_ERROR, "boom"));

        AiResponses.TaskLogResponse response = service.runTask("ch-1", "AUTO_POST");

        assertThat(response.status()).isEqualTo(AiTaskLog.Status.FAILED.name());
        // 用的是频道指定模型，不是平台默认
        verify(modelConfigService).providerFor(channelModel);
        verify(modelConfigService, never()).providerFor(defaultModel);
        // 日志要能回答「这次用的是哪个模型」
        AiTaskLog logged = captureInsertedLog();
        assertThat(logged.getModelConfigId()).isEqualTo("cfg-ch");
        assertThat(logged.getModel()).isEqualTo("channel-model");
    }

    @Test
    void should_fallback_to_default_model_when_channel_model_unavailable() {
        ChannelAiSetting setting = setting("AUTO_POST", 1);
        setting.setModelConfigId("cfg-gone");
        when(settingsService.findSetting("ch-1")).thenReturn(setting);
        when(taskLogMapper.selectByKey(anyString(), anyString(), anyString())).thenReturn(null);
        when(taskLogMapper.countSuccessBetween(anyString(), anyString(), any(), any())).thenReturn(0L);
        when(taskLogMapper.insert(any(AiTaskLog.class))).thenReturn(1);
        when(taskLogMapper.updateById(any(AiTaskLog.class))).thenReturn(1);
        when(settingsService.ensureAssistantAccountInternal("ch-1")).thenReturn("assistant-1");
        when(contextService.recentChannelPosts(eq("ch-1"), any(), any(Integer.class))).thenReturn(List.of(post()));
        when(channelMapper.selectById("ch-1")).thenReturn(channel());

        AiModelConfig defaultModel = modelConfig();
        when(modelConfigService.resolveDefault()).thenReturn(defaultModel);
        when(modelConfigService.findEnabled("cfg-gone")).thenReturn(null);
        AiProvider provider = mock(AiProvider.class);
        when(modelConfigService.providerFor(defaultModel)).thenReturn(provider);
        when(provider.chat(any())).thenThrow(new AiProviderException(AiProviderException.UPSTREAM_ERROR, "boom"));

        AiResponses.TaskLogResponse response = service.runTask("ch-1", "AUTO_POST");

        assertThat(response.status()).isEqualTo(AiTaskLog.Status.FAILED.name());
        verify(modelConfigService).providerFor(defaultModel);
        // 回落时日志记录默认模型，而不是已失效的频道配置 id
        AiTaskLog logged = captureInsertedLog();
        assertThat(logged.getModelConfigId()).isEqualTo("cfg-1");
        assertThat(logged.getModel()).isEqualTo("test-model");
    }

    private ChannelAiSetting setting(String taskType, int perDay) {
        ChannelAiSetting setting = new ChannelAiSetting();
        setting.setId("set-1");
        setting.setChannelId("ch-1");
        setting.setEnabled(true);
        setting.setNickname("频道助手");
        setting.setAutoPostPerDay(perDay);
        setting.setAutoCommentPerDay(perDay);
        setting.setAutoLikePerDay(perDay);
        setting.setDailySummaryHour(9);
        setting.setCreatedAt(LocalDateTime.now());
        setting.setUpdatedAt(LocalDateTime.now());
        return setting;
    }

    private AiModelConfig modelConfig() {
        AiModelConfig config = new AiModelConfig();
        config.setId("cfg-1");
        config.setName("本地模型");
        config.setBaseUrl("http://localhost:1234/v1");
        config.setModel("test-model");
        config.setEnabled(true);
        config.setMaxTokens(1024);
        return config;
    }


    private AiTaskLog captureInsertedLog() {
        ArgumentCaptor<AiTaskLog> captor = ArgumentCaptor.forClass(AiTaskLog.class);
        verify(taskLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    private AiContextPost post() {
        AiContextPost post = new AiContextPost();
        post.setId("post-1");
        post.setAuthorId("user-2");
        post.setTitle("露营攻略");
        post.setContent("帐篷怎么选");
        return post;
    }

    private Channel channel() {
        Channel channel = new Channel();
        channel.setId("ch-1");
        channel.setName("露营岛");
        return channel;
    }
}
