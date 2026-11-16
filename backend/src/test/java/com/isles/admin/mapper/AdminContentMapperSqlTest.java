package com.isles.admin.mapper;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 后台内容治理 SQL 与真实表结构的静态一致性校验。
 *
 * <p>背景：{@code updateCommentStatus} 曾写成 {@code SET status = ?, updated_at = CURRENT_TIMESTAMP(3)}，
 * 但 {@code comments} 表没有 {@code updated_at} 列 → 评论类举报处置直接 BadSqlGrammarException（500）。
 * 这里把「SQL 里 SET 的列必须真实存在」变成测试，避免同类字段错位再次上线。
 */
class AdminContentMapperSqlTest {

    /** 语句 → 目标表。 */
    private static final List<String> STATUS_UPDATES = List.of("updatePostStatus", "updateCommentStatus");

    @Test
    void status_updates_must_only_reference_existing_columns() throws IOException {
        Path schema = locateSchema();
        String ddl = Files.readString(schema, StandardCharsets.UTF_8);

        for (String methodName : STATUS_UPDATES) {
            String sql = updateSqlOf(methodName);
            String table = targetTableOf(sql);
            Set<String> columns = columnsOf(ddl, table);

            assertThat(columns).as("%s 表应存在于 database/schema.sql", table).isNotEmpty();
            for (String column : setColumnsOf(sql)) {
                assertThat(columns)
                        .as("%s 的 SET 列 %s 必须是 %s 表真实存在的列", methodName, column, table)
                        .contains(column);
            }
        }
    }

    @Test
    void comment_status_update_must_not_touch_updated_at() {
        // comments 只有 created_at / deleted_at：显式锁死，防止有人「顺手」补一列 updated_at
        assertThat(updateSqlOf("updateCommentStatus")).doesNotContain("updated_at");
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    private String updateSqlOf(String methodName) {
        for (Method method : AdminContentMapper.class.getMethods()) {
            if (method.getName().equals(methodName)) {
                Update update = method.getAnnotation(Update.class);
                assertThat(update).as("%s 应有 @Update", methodName).isNotNull();
                return update.value()[0];
            }
        }
        throw new AssertionError("方法不存在：" + methodName);
    }

    private String targetTableOf(String sql) {
        Matcher matcher = Pattern.compile("UPDATE\\s+(\\w+)", Pattern.CASE_INSENSITIVE).matcher(sql);
        assertThat(matcher.find()).as("SQL 应形如 UPDATE table SET ...").isTrue();
        return matcher.group(1).toLowerCase(Locale.ROOT);
    }

    /** 取 SET 与 WHERE 之间、每个赋值左侧的列名。 */
    private Set<String> setColumnsOf(String sql) {
        Matcher matcher = Pattern.compile("SET\\s+(.+?)\\s+WHERE", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(sql);
        assertThat(matcher.find()).as("SQL 应包含 SET ... WHERE").isTrue();
        Set<String> columns = new LinkedHashSet<>();
        for (String assignment : matcher.group(1).split(",")) {
            String column = assignment.split("=")[0].trim();
            if (!column.isEmpty()) {
                columns.add(column.toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }

    private Set<String> columnsOf(String ddl, String table) {
        Matcher block = Pattern.compile("CREATE TABLE `?" + table + "`?\\s*\\(([\\s\\S]*?)\\n\\)",
                Pattern.CASE_INSENSITIVE).matcher(ddl);
        assertThat(block.find()).as("schema.sql 中应有 %s 建表语句", table).isTrue();
        Set<String> columns = new LinkedHashSet<>();
        Matcher column = Pattern.compile("^\\s{2,}`(\\w+)`", Pattern.MULTILINE).matcher(block.group(1));
        while (column.find()) {
            columns.add(column.group(1).toLowerCase(Locale.ROOT));
        }
        return columns;
    }

    private Path locateSchema() {
        for (String candidate : List.of("../database/schema.sql", "database/schema.sql")) {
            Path path = Paths.get(candidate);
            if (Files.exists(path)) {
                return path;
            }
        }
        throw new AssertionError("找不到 database/schema.sql（工作目录：" + Paths.get("").toAbsolutePath() + "）");
    }
}
