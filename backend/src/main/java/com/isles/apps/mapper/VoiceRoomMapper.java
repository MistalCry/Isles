package com.isles.apps.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.apps.entity.VoiceRoom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 语音房数据访问（V10）。
 */
@Mapper
public interface VoiceRoomMapper extends BaseMapper<VoiceRoom> {

    /** 频道内开启中的房间，最新在前。 */
    @Select("""
            SELECT * FROM voice_rooms
            WHERE channel_id = #{channelId} AND status = 'OPEN'
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<VoiceRoom> selectOpenByChannel(@Param("channelId") String channelId, @Param("limit") int limit);

    /** 某人某频道是否已有开启中的房间（防刷屏：一人一频道一房）。 */
    @Select("""
            SELECT COUNT(*) FROM voice_rooms
            WHERE channel_id = #{channelId} AND host_id = #{hostId} AND status = 'OPEN'
            """)
    long countOpenByHost(@Param("channelId") String channelId, @Param("hostId") String hostId);
}
