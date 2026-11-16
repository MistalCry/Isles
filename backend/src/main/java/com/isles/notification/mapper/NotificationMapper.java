package com.isles.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.notification.entity.Notification;
import com.isles.notification.mapper.row.UnreadCategoryRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 通知数据访问。
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    /**
     * 批量插入（站内信群发）：一条多行 INSERT，单批 ≤ 500 行，避免逐行往返与超长 SQL。
     */
    @Insert("""
            <script>
            INSERT INTO notifications
              (id, user_id, type, category, channel_id, post_id, comment_id, actor_id, title, content,
               payload, is_read, created_at)
            VALUES
            <foreach item="item" collection="items" separator=",">
              (#{item.id}, #{item.userId}, #{item.type}, #{item.category}, #{item.channelId}, #{item.postId},
               #{item.commentId}, #{item.actorId}, #{item.title}, #{item.content}, #{item.payload},
               #{item.isRead}, #{item.createdAt})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("items") List<Notification> items);


    /**
     * 通知列表（游标分页，可按频道 / 类型 / 分类 / 未读筛选）。
     */
    @Select("""
            <script>
            SELECT * FROM notifications
            WHERE user_id = #{userId}
            <if test="channelId != null">AND channel_id = #{channelId}</if>
            <if test="type != null">AND type = #{type}</if>
            <if test="category != null">AND category = #{category}</if>
            <if test="unreadOnly">AND is_read = 0</if>
            <if test="cursorId != null">AND id &lt; #{cursorId}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Notification> selectPage(@Param("userId") String userId,
                                  @Param("channelId") String channelId,
                                  @Param("type") String type,
                                  @Param("category") String category,
                                  @Param("unreadOnly") boolean unreadOnly,
                                  @Param("cursorId") String cursorId,
                                  @Param("limit") int limit);

    /**
     * 全部标记已读（可按频道 / 分类限定）。
     */
    @Update("""
            <script>
            UPDATE notifications SET is_read = 1, read_at = CURRENT_TIMESTAMP(3)
            WHERE user_id = #{userId} AND is_read = 0
            <if test="channelId != null">AND channel_id = #{channelId}</if>
            <if test="category != null">AND category = #{category}</if>
            </script>
            """)
    int markAllRead(@Param("userId") String userId,
                    @Param("channelId") String channelId,
                    @Param("category") String category);

    /**
     * 未读数按分类聚合（前端红点）。
     */
    @Select("""
            SELECT category, COUNT(*) AS unread_count FROM notifications
            WHERE user_id = #{userId} AND is_read = 0
            GROUP BY category
            """)
    List<UnreadCategoryRow> selectUnreadByCategory(@Param("userId") String userId);
}
