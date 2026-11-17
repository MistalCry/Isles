package com.isles.agent.service;

import com.isles.agent.dto.AgentDtos;
import com.isles.agent.entity.AgentProfile;
import com.isles.agent.mapper.AgentProfileMapper;
import com.isles.agent.mapper.AgentRunMapper;
import com.isles.ai.entity.AiModelConfig;
import com.isles.ai.service.AiModelConfigService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.json.JsonSupport;
import com.isles.platform.audit.PlatformAuditService;
import com.isles.platform.setting.PlatformSettingService;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

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
 * 代理账号资料单元测试（P0-2）：头像与昵称必须同步到 {@code users}，否则社区里看到的是没头像的空壳。
 */
class AgentProfileServiceTest {

    private AgentProfileMapper profileMapper;
    private UserMapper userMapper;
    private AiModelConfigService modelConfigService;
    private AgentProfileService service;

    @BeforeEach
    void setUp() {
        profileMapper = mock(AgentProfileMapper.class);
        userMapper = mock(UserMapper.class);
        modelConfigService = mock(AiModelConfigService.class);
        when(profileMapper.insert(any(AgentProfile.class))).thenReturn(1);
        when(userMapper.insert(any(User.class))).thenReturn(1);
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.selectById(anyString())).thenAnswer(invocation -> {
            User user = new User();
            user.setId(invocation.getArgument(0));
            user.setUsername("agent_x");
            user.setNickname("岛民小蓝");
            user.setAvatarUrl("/uploads/avatar.png");
            return user;
        });
        service = new AgentProfileService(profileMapper, mock(AgentRunMapper.class), userMapper,
                mock(PasswordEncoder.class), new JsonSupport(), mock(PlatformAuditService.class),
                mock(PlatformSettingService.class), new AgentModelService(modelConfigService));
    }

    @Test
    void should_sync_avatar_and_nickname_on_create() {
        AgentDtos.AgentView view = service.create(request("/uploads/agent-avatar.png"), "admin-1");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getAvatarUrl()).isEqualTo("/uploads/agent-avatar.png");
        assertThat(captor.getValue().getNickname()).isEqualTo("岛民小蓝");
        assertThat(captor.getValue().getIsAgent()).isTrue();
        assertThat(view.avatarUrl()).isEqualTo("/uploads/avatar.png");
        assertThat(view.nickname()).isEqualTo("岛民小蓝");
    }

    @Test
    void should_update_avatar_and_nickname() {
        AgentProfile profile = profile();
        when(profileMapper.selectByUserId("agent-1")).thenReturn(profile);
        when(profileMapper.updateById(any(AgentProfile.class))).thenReturn(1);

        service.update("agent-1", request("/uploads/new-avatar.png"), "admin-1");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertThat(captor.getValue().getAvatarUrl()).isEqualTo("/uploads/new-avatar.png");
        assertThat(captor.getValue().getNickname()).isEqualTo("岛民小蓝");
    }

    @Test
    void should_clear_avatar_when_blank() {
        when(profileMapper.selectByUserId("agent-1")).thenReturn(profile());
        when(profileMapper.updateById(any(AgentProfile.class))).thenReturn(1);

        service.update("agent-1", request(""), "admin-1");

        // updateById 忽略 null：清空头像必须补一次显式 set null
        verify(userMapper).update(eq(null), any());
    }

    // ------------------------------------------------------------------
    // 第 19 轮 P0-2：每个代理可选模型
    // ------------------------------------------------------------------

    @Test
    void should_store_model_config_and_expose_effective_name() {
        AiModelConfig config = new AiModelConfig();
        config.setId("cfg-2");
        config.setName("Qwen 2.5 7B");
        config.setModel("qwen2.5-7b");
        config.setEnabled(true);
        when(modelConfigService.findEnabled("cfg-2")).thenReturn(config);

        AgentDtos.AgentView view = service.create(request("/uploads/agent-avatar.png", "cfg-2"), "admin-1");

        ArgumentCaptor<AgentProfile> captor = ArgumentCaptor.forClass(AgentProfile.class);
        verify(profileMapper).insert(captor.capture());
        assertThat(captor.getValue().getModelConfigId()).isEqualTo("cfg-2");
        assertThat(view.modelConfigId()).isEqualTo("cfg-2");
        assertThat(view.modelName()).isEqualTo("Qwen 2.5 7B");
    }

    @Test
    void should_reject_model_that_is_missing_or_disabled() {
        when(modelConfigService.findEnabled("cfg-gone")).thenReturn(null);

        assertThatThrownBy(() -> service.create(request("/a.png", "cfg-gone"), "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AGENT_MODEL_UNAVAILABLE));
        // 422 之前不落库：人设与账号都不该建出来
        verify(profileMapper, never()).insert(any(AgentProfile.class));
    }

    @Test
    void should_clear_model_config_when_blank() {
        when(profileMapper.selectByUserId("agent-1")).thenReturn(profile());
        when(profileMapper.updateById(any(AgentProfile.class))).thenReturn(1);

        service.update("agent-1", request("", "  "), "admin-1");

        // updateById 忽略 null：改成「跟随平台默认」必须补一次显式 set null
        verify(profileMapper).update(eq(null), any());
    }

    private AgentDtos.SaveAgentRequest request(String avatarUrl) {
        return request(avatarUrl, null);
    }

    private AgentDtos.SaveAgentRequest request(String avatarUrl, String modelConfigId) {
        return new AgentDtos.SaveAgentRequest(null, "岛民小蓝", "喜欢露营", List.of("露营"), "casual",
                List.of(List.of(20, 24)), 2, 8, List.of("ch-1"), null, "ACTIVE", avatarUrl, modelConfigId);
    }

    private AgentProfile profile() {
        AgentProfile profile = new AgentProfile();
        profile.setId("profile-1");
        profile.setUserId("agent-1");
        profile.setDisplayName("岛民小蓝");
        profile.setStatus("ACTIVE");
        profile.setPostsPerDay(2);
        profile.setCommentsPerDay(8);
        return profile;
    }
}
