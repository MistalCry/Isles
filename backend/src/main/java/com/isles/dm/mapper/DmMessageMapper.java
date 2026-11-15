package com.isles.dm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.dm.entity.DmMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 私信消息数据访问（V4 {@code dm_messages}）。
 */
@Mapper
public interface DmMessageMapper extends BaseMapper<DmMessage> {

    /**
     * 会话历史消息：ULID 倒序游标分页（{@code cursorId} 为上一页最旧一条消息 ID）。
     */
    @Select("""
            <script>
            SELECT * FROM dm_messages
            WHERE conversation_id = #{conversationId}
            <if test="cursorId != null">AND id &lt; #{cursorId}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<DmMessage> selectHistory(@Param("conversationId") String conversationId,
                                  @Param("cursorId") String cursorId,
                                  @Param("limit") int limit);

    /**
     * 发送重试去重：同一会话同一发送方的 client_message_id 只落一条。
     */
    @Select("""
            SELECT * FROM dm_messages
            WHERE conversation_id = #{conversationId}
              AND sender_id = #{senderId}
              AND client_message_id = #{clientMessageId}
            LIMIT 1
            """)
    DmMessage selectByClientMessageId(@Param("conversationId") String conversationId,
                                      @Param("senderId") String senderId,
                                      @Param("clientMessageId") String clientMessageId);

    /**
     * 批量已读：把会话中发给当前用户的未读消息标记为已读，返回影响行数。
     */
    @Update("""
            UPDATE dm_messages
            SET is_read = 1, read_at = #{readAt}
            WHERE conversation_id = #{conversationId} AND receiver_id = #{receiverId} AND is_read = 0
            """)
    int markRead(@Param("conversationId") String conversationId,
                 @Param("receiverId") String receiverId,
                 @Param("readAt") LocalDateTime readAt);
}
