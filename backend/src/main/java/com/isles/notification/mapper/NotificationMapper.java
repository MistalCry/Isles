package com.isles.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.notification.entity.Notification;
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
     * 通知列表（游标分页，可按频道 / 类型 / 未读筛选）。
     */
    @Select("""
            <script>
            SELECT * FROM notifications
            WHERE user_id = #{userId}
            <if test="channelId != null">AND channel_id = #{channelId}</if>
            <if test="type != null">AND type = #{type}</if>
            <if test="unreadOnly">AND is_read = 0</if>
            <if test="cursorId != null">AND id &lt; #{cursorId}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Notification> selectPage(@Param("userId") String userId,
                                  @Param("channelId") String channelId,
                                  @Param("type") String type,
                                  @Param("unreadOnly") boolean unreadOnly,
                                  @Param("cursorId") String cursorId,
                                  @Param("limit") int limit);

    /**
     * 全部标记已读。
     */
    @Update("""
            UPDATE notifications SET is_read = 1
            WHERE user_id = #{userId} AND is_read = 0
            """)
    int markAllRead(@Param("userId") String userId);
}
