package com.isles.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.ai.entity.AiMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 消息数据访问：会话内消息游标分页、提示词历史消息与每日配额统计。
 */
@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {

    /**
     * 会话消息：按 ULID 倒序取最新一页（前端展示时反转），{@code cursorId} 为上一页最旧消息 id。
     */
    @Select("""
            <script>
            SELECT * FROM ai_messages
            WHERE conversation_id = #{conversationId}
            <if test="cursorId != null">AND id &lt; #{cursorId}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<AiMessage> selectNewest(@Param("conversationId") String conversationId,
                                 @Param("cursorId") String cursorId,
                                 @Param("limit") int limit);

    /**
     * 拼装提示词用的最近历史消息（倒序取出后由服务层反转）。
     */
    @Select("""
            SELECT * FROM ai_messages
            WHERE conversation_id = #{conversationId} AND status = 'COMPLETED'
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<AiMessage> selectRecentCompleted(@Param("conversationId") String conversationId,
                                          @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM ai_messages WHERE conversation_id = #{conversationId}")
    long countByConversation(@Param("conversationId") String conversationId);

    /**
     * 用户当日已发送提问数（每日配额，docs/07 §7 AI 问答配额）。
     */
    @Select("""
            SELECT COUNT(*) FROM ai_messages m
            JOIN ai_conversations c ON c.id = m.conversation_id
            WHERE c.user_id = #{userId} AND m.role = 'USER' AND m.created_at >= #{since}
            """)
    long countUserQuestionsSince(@Param("userId") String userId, @Param("since") LocalDateTime since);
}
