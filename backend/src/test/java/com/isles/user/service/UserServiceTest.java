package com.isles.user.service;

import com.isles.channel.service.ChannelService;
import com.isles.user.dto.UserResponse;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 用户资料响应单元测试（V13）：AI 代理账号必须能被前台识别（docs/12 §6）。
 */
class UserServiceTest {

    @Test
    void should_expose_is_agent_flag() {
        UserService service = new UserService(mock(UserMapper.class), mock(ChannelService.class));

        User agent = user("user-1", true);
        User human = user("user-2", false);

        assertThat(service.toResponse(agent).isAgent()).isTrue();
        assertThat(service.toResponse(human).isAgent()).isFalse();
    }

    @Test
    void should_treat_null_is_agent_as_human() {
        UserService service = new UserService(mock(UserMapper.class), mock(ChannelService.class));

        UserResponse response = service.toResponse(user("user-3", null));

        // 历史数据（V13 之前的行）is_agent 可能为 null，绝不能因此判成代理
        assertThat(response.isAgent()).isFalse();
    }

    private User user(String id, Boolean isAgent) {
        User user = new User();
        user.setId(id);
        user.setUsername("islander");
        user.setNickname("岛民");
        user.setStatus(User.Status.ACTIVE.name());
        user.setIsAgent(isAgent);
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }
}
