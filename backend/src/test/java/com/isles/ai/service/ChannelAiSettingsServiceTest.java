package com.isles.ai.service;

import com.isles.ai.dto.AiRequests;
import com.isles.ai.dto.AiResponses;
import com.isles.ai.entity.AiTaskLog;
import com.isles.ai.entity.ChannelAiSetting;
import com.isles.ai.mapper.AiTaskLogMapper;
import com.isles.ai.mapper.ChannelAiSettingMapper;
import com.isles.channel.entity.Channel;
import com.isles.channel.entity.ChannelMember;
import com.isles.channel.entity.Role;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.mapper.ChannelMemberMapper;
import com.isles.channel.mapper.RoleMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 频道助手设置与虚拟账号单元测试（docs/03 FR-MGR-08 / FR-ASSIST-01）：
 * 管理权限校验、默认设置创建、虚拟账号复用 users 表并以普通成员入频道。
 */
class ChannelAiSettingsServiceTest {

    private ChannelAiSettingMapper settingMapper;
    private AiTaskLogMapper taskLogMapper;
    private ChannelMapper channelMapper;
    private ChannelMemberMapper channelMemberMapper;
    private RoleMapper roleMapper;
    private UserMapper userMapper;
    private ChannelAccessService accessService;
    private PasswordEncoder passwordEncoder;
    private ChannelAiSettingsService service;

    @BeforeEach
    void setUp() {
        settingMapper = mock(ChannelAiSettingMapper.class);
        taskLogMapper = mock(AiTaskLogMapper.class);
        channelMapper = mock(ChannelMapper.class);
        channelMemberMapper = mock(ChannelMemberMapper.class);
        roleMapper = mock(RoleMapper.class);
        userMapper = mock(UserMapper.class);
        accessService = mock(ChannelAccessService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new ChannelAiSettingsService(settingMapper, taskLogMapper, channelMapper, channelMemberMapper,
                roleMapper, userMapper, accessService, passwordEncoder);
    }

    @Test
    void should_reject_settings_read_without_channel_ai_manage_permission() {
        doThrow(new AppException(ErrorCode.FORBIDDEN))
                .when(accessService).requirePermission("ch-1", "user-1", ChannelPermissions.AI_MANAGE);

        assertThatThrownBy(() -> service.getSettings("ch-1", "user-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void should_create_default_setting_on_first_save() {
        when(settingMapper.selectOne(any())).thenReturn(null);
        when(settingMapper.insert(any(ChannelAiSetting.class))).thenReturn(1);

        AiResponses.AssistantSettingsResponse response = service.saveSettings("ch-1",
                new AiRequests.AssistantSettingsRequest("岛灵", "/uploads/a.png", null, "频道小助手", "温和幽默",
                        true, true, false, false, false, false, false, true, 9, 1, 3, 10),
                "user-1");

        assertThat(response.nickname()).isEqualTo("岛灵");
        assertThat(response.enabled()).isTrue();
        assertThat(response.summaryDailyEnabled()).isTrue();
        assertThat(response.dailySummaryHour()).isEqualTo(9);
        verify(settingMapper).insert(any(ChannelAiSetting.class));
    }

    @Test
    void should_create_assistant_user_and_membership_when_missing() {
        ChannelAiSetting setting = setting("set-1", "ch-1", null);
        setting.setNickname("岛灵");
        when(settingMapper.selectOne(any())).thenReturn(setting);
        when(accessService.requireActiveChannel("ch-1")).thenReturn(channel());
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.insert(any(User.class))).thenReturn(1);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(roleMapper.selectOne(any())).thenReturn(role());
        when(accessService.findMember("ch-1", null)).thenReturn(null);
        when(accessService.findMember(eq("ch-1"), any())).thenReturn(null);
        when(channelMemberMapper.insert(any(ChannelMember.class))).thenReturn(1);
        when(channelMapper.addMemberCount("ch-1", 1)).thenReturn(1);
        when(settingMapper.updateById(any(ChannelAiSetting.class))).thenReturn(1);

        String assistantUserId = service.ensureAssistantAccountInternal("ch-1");

        assertThat(assistantUserId).isNotBlank();
        assertThat(setting.getAssistantUserId()).isEqualTo(assistantUserId);
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(userCaptor.capture());
        assertThat(userCaptor.getValue().getUsername()).startsWith("aiassistant_");
        assertThat(userCaptor.getValue().getNickname()).isEqualTo("岛灵");
        verify(channelMemberMapper).insert(any(ChannelMember.class));
        verify(channelMapper).addMemberCount("ch-1", 1);
    }

    @Test
    void should_reuse_existing_assistant_account() {
        ChannelAiSetting setting = setting("set-1", "ch-1", "assistant-1");
        when(settingMapper.selectOne(any())).thenReturn(setting);
        when(accessService.requireActiveChannel("ch-1")).thenReturn(channel());
        User assistant = new User();
        assistant.setId("assistant-1");
        assistant.setStatus(User.Status.ACTIVE.name());
        when(userMapper.selectById("assistant-1")).thenReturn(assistant);
        ChannelMember member = new ChannelMember();
        member.setStatus(ChannelMember.Status.ACTIVE.name());
        when(accessService.findMember("ch-1", "assistant-1")).thenReturn(member);

        assertThat(service.ensureAssistantAccountInternal("ch-1")).isEqualTo("assistant-1");
        verify(userMapper, never()).insert(any(User.class));
        verify(channelMemberMapper, never()).insert(any(ChannelMember.class));
    }

    @Test
    void should_reject_manual_account_creation_without_permission() {
        doThrow(new AppException(ErrorCode.FORBIDDEN))
                .when(accessService).requirePermission("ch-1", "user-1", ChannelPermissions.AI_MANAGE);

        assertThatThrownBy(() -> service.ensureAssistantAccount("ch-1", "user-1"))
                .isInstanceOf(AppException.class);
    }

    @Test
    void should_list_task_logs_with_permission() {
        AiTaskLog taskLog = new AiTaskLog();
        taskLog.setId("log-1");
        taskLog.setChannelId("ch-1");
        taskLog.setTaskType("DAILY_SUMMARY");
        taskLog.setStatus("SUCCESS");
        taskLog.setCreatedAt(LocalDateTime.now());
        when(taskLogMapper.selectPage("ch-1", null, 20, 0)).thenReturn(List.of(taskLog));
        when(taskLogMapper.countPage("ch-1", null)).thenReturn(1L);

        AiResponses.TaskLogPage page = service.listTaskLogs("ch-1", "user-1", null, 1, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).taskType()).isEqualTo("DAILY_SUMMARY");
    }

    private ChannelAiSetting setting(String id, String channelId, String assistantUserId) {
        ChannelAiSetting setting = new ChannelAiSetting();
        setting.setId(id);
        setting.setChannelId(channelId);
        setting.setAssistantUserId(assistantUserId);
        setting.setNickname("频道助手");
        setting.setEnabled(true);
        setting.setCreatedAt(LocalDateTime.now());
        setting.setUpdatedAt(LocalDateTime.now());
        return setting;
    }

    private Channel channel() {
        Channel channel = new Channel();
        channel.setId("ch-1");
        channel.setName("测试频道");
        channel.setStatus(Channel.Status.ACTIVE.name());
        channel.setType(Channel.Type.PUBLIC.name());
        channel.setVisibility(Channel.Visibility.PUBLIC_VISIBLE.name());
        return channel;
    }

    private Role role() {
        Role role = new Role();
        role.setId("role-member");
        role.setChannelId("ch-1");
        role.setName("普通成员");
        role.setPermissions("[\"post.create\",\"comment.create\"]");
        return role;
    }
}
