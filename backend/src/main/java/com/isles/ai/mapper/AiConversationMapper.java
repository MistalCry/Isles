package com.isles.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.ai.entity.AiConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 会话数据访问：按用户 + 更新时间游标分页（侧边栏历史会话）。
 */
@Mapper
public interface AiConversationMapper extends BaseMapper<AiConversation> {

    /**
     * 会话列表：按 updated_at 倒序，游标为 (updatedAt, id) 组合，避免同一毫秒内分页重复/遗漏。
     */
    @Select("""
            <script>
            SELECT * FROM ai_conversations
            WHERE user_id = #{userId} AND deleted_at IS NULL
            <if test="scope != null">AND scope = #{scope}</if>
            <if test="channelId != null">AND channel_id = #{channelId}</if>
            <if test="cursorAt != null">
              AND (updated_at &lt; #{cursorAt} OR (updated_at = #{cursorAt} AND id &lt; #{cursorId}))
            </if>
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<AiConversation> selectPageByUser(@Param("userId") String userId,
                                          @Param("scope") String scope,
                                          @Param("channelId") String channelId,
                                          @Param("cursorAt") LocalDateTime cursorAt,
                                          @Param("cursorId") String cursorId,
                                          @Param("limit") int limit);
}
