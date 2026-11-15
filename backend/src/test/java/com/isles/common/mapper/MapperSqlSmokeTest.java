package com.isles.common.mapper;

import com.isles.auth.mapper.RefreshTokenMapper;
import com.isles.channel.mapper.BoardMapper;
import com.isles.channel.mapper.ChannelAuditLogMapper;
import com.isles.channel.mapper.ChannelInviteMapper;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.mapper.ChannelMemberMapper;
import com.isles.channel.mapper.ChannelRecommendationMapper;
import com.isles.channel.mapper.ChannelStatsMapper;
import com.isles.channel.mapper.ChannelViewLogMapper;
import com.isles.channel.mapper.DailyChannelStatsMapper;
import com.isles.channel.mapper.JoinRequestMapper;
import com.isles.channel.mapper.ReportMapper;
import com.isles.comment.mapper.CommentMapper;
import com.isles.notification.mapper.NotificationMapper;
import com.isles.post.mapper.PostMapper;
import com.isles.search.mapper.HotKeywordMapper;
import com.isles.search.mapper.SearchHistoryMapper;
import com.isles.search.mapper.SearchLogMapper;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MyBatis 注解 SQL 冒烟测试：解析所有含注解 SQL 的 Mapper，校验动态 SQL（script/if/foreach）
 * 与 OGNL 表达式语法合法。不连接数据库、不加载 Spring 上下文，防止运行期才暴露 SQL 脚本错误。
 */
class MapperSqlSmokeTest {

    @Test
    void should_parse_all_annotated_mapper_sql() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);

        configuration.addMapper(ChannelMapper.class);
        configuration.addMapper(PostMapper.class);
        configuration.addMapper(CommentMapper.class);
        configuration.addMapper(NotificationMapper.class);
        configuration.addMapper(SearchHistoryMapper.class);
        configuration.addMapper(SearchLogMapper.class);
        configuration.addMapper(RefreshTokenMapper.class);
        configuration.addMapper(BoardMapper.class);
        configuration.addMapper(ChannelInviteMapper.class);
        // V2 新增 Mapper
        configuration.addMapper(ChannelMemberMapper.class);
        configuration.addMapper(JoinRequestMapper.class);
        configuration.addMapper(ChannelAuditLogMapper.class);
        configuration.addMapper(ReportMapper.class);
        configuration.addMapper(DailyChannelStatsMapper.class);
        configuration.addMapper(ChannelViewLogMapper.class);
        configuration.addMapper(ChannelRecommendationMapper.class);
        configuration.addMapper(ChannelStatsMapper.class);
        configuration.addMapper(HotKeywordMapper.class);

        assertThat(configuration.hasStatement("com.isles.post.mapper.PostMapper.selectChannelLatest")).isTrue();
        assertThat(configuration.hasStatement("com.isles.post.mapper.PostMapper.selectChannelHot")).isTrue();
        assertThat(configuration.hasStatement("com.isles.post.mapper.PostMapper.selectFeedLatest")).isTrue();
        assertThat(configuration.hasStatement("com.isles.post.mapper.PostMapper.selectFeedHot")).isTrue();
        assertThat(configuration.hasStatement("com.isles.post.mapper.PostMapper.searchVisiblePosts")).isTrue();
        assertThat(configuration.hasStatement("com.isles.post.mapper.PostMapper.selectHotGlobal")).isTrue();
        assertThat(configuration.hasStatement("com.isles.post.mapper.PostMapper.selectChannelManage")).isTrue();
        assertThat(configuration.hasStatement("com.isles.channel.mapper.ChannelMapper.searchVisible")).isTrue();
        assertThat(configuration.hasStatement("com.isles.channel.mapper.ChannelMapper.searchVisibleByMembers"))
                .isTrue();
        assertThat(configuration.hasStatement("com.isles.channel.mapper.ChannelMemberMapper.countCommonMembers"))
                .isTrue();
        assertThat(configuration.hasStatement("com.isles.channel.mapper.ChannelAuditLogMapper.selectPage"))
                .isTrue();
        assertThat(configuration.hasStatement("com.isles.channel.mapper.ReportMapper.selectPage")).isTrue();
        assertThat(configuration.hasStatement("com.isles.channel.mapper.DailyChannelStatsMapper.upsert")).isTrue();
        assertThat(configuration.hasStatement("com.isles.channel.mapper.ChannelViewLogMapper.selectActiveUserIds"))
                .isTrue();
        assertThat(configuration.hasStatement("com.isles.search.mapper.HotKeywordMapper.upsert")).isTrue();
        assertThat(configuration.hasStatement("com.isles.comment.mapper.CommentMapper.selectTopLiked")).isTrue();
        assertThat(configuration.hasStatement("com.isles.notification.mapper.NotificationMapper.selectPage")).isTrue();
    }
}
