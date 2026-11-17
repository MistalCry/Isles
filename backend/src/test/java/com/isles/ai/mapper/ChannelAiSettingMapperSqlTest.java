package com.isles.ai.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 频道助手设置 mapper SQL 校验（第 21 轮 P1-3）：不连库，直接读注解里的 SQL。
 *
 * <p>背景：`scanAndRun` 原先只按 `enabled=1` 取设置，频道解散后任务照跑，
 * 每天先烧 token 再在发帖处 404。这里锁住「SQL 层必须 join channels 过滤状态」。
 */
class ChannelAiSettingMapperSqlTest {

    @Test
    void should_filter_dissolved_channels_in_scannable_sql() throws NoSuchMethodException {
        Select select = ChannelAiSettingMapper.class.getMethod("selectScannable").getAnnotation(Select.class);
        assertThat(select).isNotNull();
        String sql = String.join(" ", select.value());

        assertThat(sql).containsIgnoringCase("JOIN channels c ON c.id = s.channel_id");
        assertThat(sql).contains("s.enabled = 1");
        assertThat(sql).contains("c.status = 'ACTIVE'");
        assertThat(sql).contains("c.deleted_at IS NULL");
    }

    @Test
    void should_disable_assistant_by_channel() throws NoSuchMethodException {
        Update update = ChannelAiSettingMapper.class.getMethod("disableByChannel", String.class)
                .getAnnotation(Update.class);
        assertThat(update).isNotNull();
        String sql = String.join(" ", update.value());

        // 只关开关，不删人设/历史日志
        assertThat(sql).containsIgnoringCase("SET enabled = 0");
        assertThat(sql).contains("WHERE channel_id = #{channelId}");
        assertThat(sql).doesNotContainIgnoringCase("DELETE");
    }
}
