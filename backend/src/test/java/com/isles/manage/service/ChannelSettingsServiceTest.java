package com.isles.manage.service;

import com.isles.ai.mapper.ChannelAiSettingMapper;
import com.isles.apps.service.ChannelAppDataCleaner;
import com.isles.channel.audit.AuditLogService;
import com.isles.channel.entity.Channel;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.mapper.ChannelMemberMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 频道设置单元测试（第 21 轮 P1-3）：解散频道必须同时关掉频道助手，否则任务会继续给死频道烧 token。
 */
class ChannelSettingsServiceTest {

    private ChannelMapper channelMapper;
    private ChannelAccessService accessService;
    private ChannelAppDataCleaner appDataCleaner;
    private ChannelAiSettingMapper aiSettingMapper;
    private ChannelSettingsService service;

    @BeforeEach
    void setUp() {
        channelMapper = mock(ChannelMapper.class);
        accessService = mock(ChannelAccessService.class);
        appDataCleaner = mock(ChannelAppDataCleaner.class);
        aiSettingMapper = mock(ChannelAiSettingMapper.class);
        service = new ChannelSettingsService(channelMapper, mock(ChannelMemberMapper.class), accessService,
                mock(ChannelService.class), mock(AuditLogService.class), appDataCleaner, aiSettingMapper);
    }

    @Test
    void should_disable_assistant_when_channel_dissolved() {
        Channel channel = new Channel();
        channel.setId("ch-1");
        channel.setStatus(Channel.Status.ACTIVE.name());
        when(accessService.requireOwner("ch-1", "owner-1"))
                .thenReturn(new ChannelAccessService.ChannelAccessContext(channel, null, null));
        when(channelMapper.updateById(any(Channel.class))).thenReturn(1);
        when(appDataCleaner.purgeChannel("ch-1"))
                .thenReturn(new ChannelAppDataCleaner.PurgeResult(0, 0, 0, 0, 0));
        when(aiSettingMapper.disableByChannel("ch-1")).thenReturn(1);

        service.dissolveChannel("ch-1", "owner-1");

        // 频道本身软删
        assertThat(channel.getStatus()).isEqualTo(Channel.Status.DISSOLVED.name());
        assertThat(channel.getDeletedAt()).isNotNull();
        // 频道级数据清理 + 助手开关关闭（双保险，防止绕过扫描器继续跑任务）
        verify(appDataCleaner).purgeChannel("ch-1");
        verify(aiSettingMapper).disableByChannel("ch-1");
    }
}
