package com.isles.dm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.dm.entity.DmConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 私信会话数据访问（V4 {@code dm_conversations}）。
 *
 * <p>未读与"最后一条消息"更新均为单条原子 UPDATE，避免读改写丢失更新（docs/00 §3.2 并发写）。
 */
@Mapper
public interface DmConversationMapper extends BaseMapper<DmConversation> {

    /**
     * 按规范化参与者查询会话（user_low_id 较小、user_high_id 较大）。
     */
    @Select("""
            SELECT * FROM dm_conversations
            WHERE user_low_id = #{lowId} AND user_high_id = #{highId}
            """)
    DmConversation selectByPair(@Param("lowId") String lowId, @Param("highId") String highId);

    /**
     * 我的会话列表：按最后消息时间倒序，{@code (last_message_at, id)} 组合游标。
     */
    @Select("""
            <script>
            SELECT * FROM dm_conversations
            WHERE (user_low_id = #{userId} OR user_high_id = #{userId})
            <if test="cursorAt != null">
              AND (last_message_at &lt; #{cursorAt}
                   OR (last_message_at = #{cursorAt} AND id &lt; #{cursorId}))
            </if>
            ORDER BY last_message_at DESC, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<DmConversation> selectPageByUser(@Param("userId") String userId,
                                          @Param("cursorAt") LocalDateTime cursorAt,
                                          @Param("cursorId") String cursorId,
                                          @Param("limit") int limit);

    /**
     * 全部会话未读总数（侧边栏红点），单条 SQL 聚合，避免逐会话查询。
     */
    @Select("""
            SELECT COALESCE(SUM(CASE WHEN user_low_id = #{userId} THEN unread_low ELSE unread_high END), 0)
            FROM dm_conversations
            WHERE user_low_id = #{userId} OR user_high_id = #{userId}
            """)
    Long selectTotalUnread(@Param("userId") String userId);

    /**
     * 发送消息后原子更新最后消息并累加接收方未读（一次 UPDATE，避免未读被覆盖）。
     *
     * @param unreadLowDelta  接收方是 low 侧时传 1，否则 0
     * @param unreadHighDelta 接收方是 high 侧时传 1，否则 0
     */
    @Update("""
            UPDATE dm_conversations
            SET last_message_id = #{messageId},
                last_message_sender_id = #{senderId},
                last_message_type = #{type},
                last_message_preview = #{preview},
                last_message_at = #{at},
                unread_low = unread_low + #{unreadLowDelta},
                unread_high = unread_high + #{unreadHighDelta},
                updated_at = #{at}
            WHERE id = #{id}
            """)
    int updateLastMessage(@Param("id") String id,
                          @Param("messageId") String messageId,
                          @Param("senderId") String senderId,
                          @Param("type") String type,
                          @Param("preview") String preview,
                          @Param("at") LocalDateTime at,
                          @Param("unreadLowDelta") int unreadLowDelta,
                          @Param("unreadHighDelta") int unreadHighDelta);

    /**
     * 进入会话后清零当前用户侧未读（只清自己那一侧，避免把对方未读也清掉）。
     */
    @Update("""
            UPDATE dm_conversations
            SET unread_low = CASE WHEN user_low_id = #{userId} THEN 0 ELSE unread_low END,
                unread_high = CASE WHEN user_high_id = #{userId} THEN 0 ELSE unread_high END,
                updated_at = #{at}
            WHERE id = #{id}
            """)
    int resetUnread(@Param("id") String id, @Param("userId") String userId, @Param("at") LocalDateTime at);
}
