package com.isles.social;

import com.isles.dm.mapper.DmConversationMapper;
import com.isles.dm.mapper.DmMessageMapper;
import com.isles.social.mapper.FriendRelationMapper;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP2 Mapper 注解 SQL 冒烟测试：解析动态 SQL 并实际构建 BoundSql（OGNL 求值），
 * 不连接数据库，提前暴露运行期才会出现的脚本/参数名错误。
 */
class SocialDmMapperSqlSmokeTest {

    private static final String CURSOR = "01M7D1DEWDPJJJ5G9Z2NNW3J74";

    private Configuration configuration;

    @BeforeEach
    void setUp() {
        configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(FriendRelationMapper.class);
        configuration.addMapper(DmConversationMapper.class);
        configuration.addMapper(DmMessageMapper.class);
    }

    @Test
    void should_parse_friend_relation_mapper_sql() {
        assertThat(sql("com.isles.social.mapper.FriendRelationMapper.selectPair",
                params("userId", "user-1", "targetUserId", "user-2")))
                .contains("friend_relations").contains("target_user_id");
        assertThat(sql("com.isles.social.mapper.FriendRelationMapper.selectByUserAndStatus",
                params("userId", "user-1", "status", "ACCEPTED", "cursorId", null, "limit", 21)))
                .contains("ORDER BY id DESC").doesNotContain("<if");
        assertThat(sql("com.isles.social.mapper.FriendRelationMapper.selectByUserAndStatus",
                params("userId", "user-1", "status", "ACCEPTED", "cursorId", CURSOR, "limit", 21)))
                .contains("id <");
        assertThat(sql("com.isles.social.mapper.FriendRelationMapper.selectIncomingPending",
                params("userId", "user-1", "cursorId", null, "limit", 21)))
                .contains("status = 'PENDING'");
        assertThat(sql("com.isles.social.mapper.FriendRelationMapper.deleteNonBlockedBetween",
                params("userId", "user-1", "targetUserId", "user-2")))
                .contains("DELETE FROM friend_relations").contains("!= 'BLOCKED'");
        assertThat(sql("com.isles.social.mapper.FriendRelationMapper.deleteBlocked",
                params("userId", "user-1", "targetUserId", "user-2")))
                .contains("status = 'BLOCKED'");
    }

    @Test
    void should_parse_dm_conversation_mapper_sql() {
        assertThat(sql("com.isles.dm.mapper.DmConversationMapper.selectByPair",
                params("lowId", "user-1", "highId", "user-2")))
                .contains("dm_conversations").contains("user_low_id");
        assertThat(sql("com.isles.dm.mapper.DmConversationMapper.selectPageByUser",
                params("userId", "user-1", "cursorAt", null, "cursorId", null, "limit", 21)))
                .contains("ORDER BY last_message_at DESC").doesNotContain("<if");
        assertThat(sql("com.isles.dm.mapper.DmConversationMapper.selectPageByUser",
                params("userId", "user-1", "cursorAt", LocalDateTime.now(), "cursorId", CURSOR, "limit", 21)))
                .contains("last_message_at <");
        assertThat(sql("com.isles.dm.mapper.DmConversationMapper.selectTotalUnread",
                params("userId", "user-1")))
                .contains("SUM(");
        assertThat(sql("com.isles.dm.mapper.DmConversationMapper.updateLastMessage",
                params("id", "conv-1", "messageId", "msg-1", "senderId", "user-1", "type", "TEXT",
                        "preview", "hi", "at", LocalDateTime.now(), "unreadLowDelta", 0, "unreadHighDelta", 1)))
                .contains("unread_high = unread_high +");
        assertThat(sql("com.isles.dm.mapper.DmConversationMapper.resetUnread",
                params("id", "conv-1", "userId", "user-1", "at", LocalDateTime.now())))
                .contains("CASE WHEN user_low_id");
    }

    @Test
    void should_parse_dm_message_mapper_sql() {
        assertThat(sql("com.isles.dm.mapper.DmMessageMapper.selectHistory",
                params("conversationId", "conv-1", "cursorId", null, "limit", 31)))
                .contains("ORDER BY id DESC").doesNotContain("<if");
        assertThat(sql("com.isles.dm.mapper.DmMessageMapper.selectHistory",
                params("conversationId", "conv-1", "cursorId", CURSOR, "limit", 31)))
                .contains("id <");
        assertThat(sql("com.isles.dm.mapper.DmMessageMapper.selectByClientMessageId",
                params("conversationId", "conv-1", "senderId", "user-1", "clientMessageId", "cid-1")))
                .contains("client_message_id =");
        assertThat(sql("com.isles.dm.mapper.DmMessageMapper.markRead",
                params("conversationId", "conv-1", "receiverId", "user-2", "readAt", LocalDateTime.now())))
                .contains("SET is_read = 1");
    }

    // ------------------------------------------------------------------

    private String sql(String statementId, Map<String, Object> params) {
        MappedStatement statement = configuration.getMappedStatement(statementId);
        return statement.getBoundSql(params).getSql();
    }

    private Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }
}
