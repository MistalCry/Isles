package com.isles.post.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 频道帖子流 SQL 契约测试：latest/essence 排除置顶，置顶只由 pinned 接口提供。
 */
class PostMapperSqlTest {

    @Test
    void latest_stream_must_exclude_pinned_posts() {
        String sql = selectSqlOf("selectChannelLatest");

        assertThat(sql).contains("is_pinned = 0");
        // 复合游标（置顶标记 + id）已随排序简化退场，避免残留半套逻辑
        assertThat(sql).doesNotContain("cursorPinned");
        assertThat(sql).doesNotContain("is_pinned DESC");
        assertThat(sql).contains("ORDER BY id DESC");
    }

    @Test
    void pinned_query_remains_the_only_pinned_source() {
        String sql = selectSqlOf("selectChannelPinned");

        assertThat(sql).contains("is_pinned = 1").contains("ORDER BY pinned_at DESC");
    }

    private String selectSqlOf(String methodName) {
        for (Method method : PostMapper.class.getMethods()) {
            if (method.getName().equals(methodName)) {
                Select select = method.getAnnotation(Select.class);
                assertThat(select).as("%s 应有 @Select", methodName).isNotNull();
                return select.value()[0];
            }
        }
        throw new AssertionError("方法不存在：" + methodName);
    }
}
