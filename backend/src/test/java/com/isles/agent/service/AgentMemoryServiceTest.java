package com.isles.agent.service;

import com.isles.agent.entity.AgentMemory;
import com.isles.agent.mapper.AgentMemoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 代理记忆单元测试（V13）：指纹归一化、7 天去重、相似话题拦截。
 */
class AgentMemoryServiceTest {

    private AgentMemoryMapper memoryMapper;
    private AgentMemoryService service;

    @BeforeEach
    void setUp() {
        memoryMapper = mock(AgentMemoryMapper.class);
        service = new AgentMemoryService(memoryMapper);
    }

    @Test
    void fingerprint_should_ignore_punctuation_and_case() {
        String left = AgentMemoryService.fingerprint("露营装备清单！");
        String right = AgentMemoryService.fingerprint("露营装备清单");
        assertThat(left).isEqualTo(right).hasSize(32);
    }

    @Test
    void should_skip_duplicate_fingerprint_within_window() {
        when(memoryMapper.countByFingerprint(eq("agent-1"), anyString(), any())).thenReturn(1L);

        String id = service.remember("agent-1", "TOPIC", "露营装备清单", "post-1");

        assertThat(id).isNull();
        verify(memoryMapper, never()).insert(any(AgentMemory.class));
    }

    @Test
    void should_insert_new_memory() {
        when(memoryMapper.countByFingerprint(eq("agent-1"), anyString(), any())).thenReturn(0L);
        when(memoryMapper.insert(any(AgentMemory.class))).thenReturn(1);

        String id = service.remember("agent-1", "TOPIC", "露营装备清单", "post-1");

        assertThat(id).isNotBlank();
        verify(memoryMapper).insert(any(AgentMemory.class));
    }

    @Test
    void should_detect_highly_similar_topic() {
        when(memoryMapper.countByFingerprint(eq("agent-1"), anyString(), any())).thenReturn(0L);
        AgentMemory memory = new AgentMemory();
        memory.setContent("露营装备清单与选购建议");
        when(memoryMapper.selectRecent(eq("agent-1"), eq("TOPIC"), any(), any(Integer.class)))
                .thenReturn(List.of(memory));

        assertThat(service.isDuplicateTopic("agent-1", "露营装备清单和选购建议")).isTrue();
    }

    @Test
    void should_allow_unrelated_topic() {
        when(memoryMapper.countByFingerprint(eq("agent-1"), anyString(), any())).thenReturn(0L);
        AgentMemory memory = new AgentMemory();
        memory.setContent("露营装备清单与选购建议");
        when(memoryMapper.selectRecent(eq("agent-1"), eq("TOPIC"), any(), any(Integer.class)))
                .thenReturn(List.of(memory));

        assertThat(service.isDuplicateTopic("agent-1", "周末海岛潜水记录")).isFalse();
    }
}
