package com.isles.ai.mapper;

import com.isles.admin.mapper.AdminChannelMapper;
import com.isles.admin.mapper.AdminReportMapper;
import com.isles.admin.mapper.AdminStatsMapper;
import com.isles.admin.mapper.AdminUserMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI/平台后台 Mapper 注解 SQL 冒烟测试：校验动态 SQL 语法可解析（不连接数据库）。
 */
class AiMapperSqlSmokeTest {

    /** 覆盖 AI 与平台后台的注解 Mapper。 */
    private static final List<Class<?>> MAPPERS = List.of(
            AiConversationMapper.class,
            AiMessageMapper.class,
            AiTaskLogMapper.class,
            AiContextMapper.class,
            AdminUserMapper.class,
            AdminChannelMapper.class,
            AdminStatsMapper.class,
            AdminReportMapper.class);

    /**
     * 非 {@code <script>} 包裹的 SQL 是纯文本，MyBatis 不会解码 XML 实体；
     * 若误写 {@code &gt;=} 会直接拼进 SQL 导致语法错误（曾导致 AI 配额统计 500）。
     */
    @Test
    void should_not_use_xml_entities_in_plain_sql() {
        for (Class<?> mapper : MAPPERS) {
            for (Method method : mapper.getDeclaredMethods()) {
                Select select = method.getAnnotation(Select.class);
                if (select == null) {
                    continue;
                }
                for (String sql : select.value()) {
                    if (sql.contains("<script>")) {
                        continue;
                    }
                    assertThat(sql)
                            .as("%s#%s 的纯文本 SQL 不应包含 XML 实体", mapper.getSimpleName(), method.getName())
                            .doesNotContain("&gt;")
                            .doesNotContain("&lt;");
                }
            }
        }
    }

    @Test
    void should_parse_all_ai_and_admin_annotated_mapper_sql() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);

        for (Class<?> mapper : MAPPERS) {
            configuration.addMapper(mapper);
        }

        assertThat(configuration.hasStatement("com.isles.ai.mapper.AiConversationMapper.selectPageByUser")).isTrue();
        assertThat(configuration.hasStatement("com.isles.ai.mapper.AiMessageMapper.selectNewest")).isTrue();
        assertThat(configuration.hasStatement("com.isles.ai.mapper.AiMessageMapper.countUserQuestionsSince")).isTrue();
        assertThat(configuration.hasStatement("com.isles.ai.mapper.AiTaskLogMapper.selectByKey")).isTrue();
        assertThat(configuration.hasStatement("com.isles.ai.mapper.AiTaskLogMapper.countSuccessBetween")).isTrue();
        assertThat(configuration.hasStatement("com.isles.ai.mapper.AiContextMapper.searchContextPosts")).isTrue();
        assertThat(configuration.hasStatement("com.isles.ai.mapper.AiContextMapper.selectMonthlyContributors")).isTrue();
        assertThat(configuration.hasStatement("com.isles.admin.mapper.AdminUserMapper.selectRowById")).isTrue();
        assertThat(configuration.hasStatement("com.isles.admin.mapper.AdminChannelMapper.selectPage")).isTrue();
        assertThat(configuration.hasStatement("com.isles.admin.mapper.AdminReportMapper.selectPage")).isTrue();
    }
}
