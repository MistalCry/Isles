package com.isles.apps.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.apps.entity.VoiceRoomSeat;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 语音房麦位数据访问（V10）。
 *
 * <p>抢座依赖数据库唯一键兜底（{@code (room_id,seat_index)}），不在应用层做「先查后插」，
 * 避免并发窗口；插入冲突由 Service 转 409。
 */
@Mapper
public interface VoiceRoomSeatMapper extends BaseMapper<VoiceRoomSeat> {

    @Select("""
            SELECT * FROM voice_room_seats
            WHERE room_id = #{roomId}
            ORDER BY seat_index ASC
            """)
    List<VoiceRoomSeat> selectByRoom(@Param("roomId") String roomId);

    @Select("SELECT * FROM voice_room_seats WHERE room_id = #{roomId} AND user_id = #{userId}")
    VoiceRoomSeat selectByRoomAndUser(@Param("roomId") String roomId, @Param("userId") String userId);

    @Delete("DELETE FROM voice_room_seats WHERE room_id = #{roomId} AND user_id = #{userId}")
    int deleteByRoomAndUser(@Param("roomId") String roomId, @Param("userId") String userId);

    @Delete("DELETE FROM voice_room_seats WHERE room_id = #{roomId}")
    int deleteByRoom(@Param("roomId") String roomId);

    @Update("UPDATE voice_room_seats SET muted = #{muted} WHERE room_id = #{roomId} AND seat_index = #{seatIndex}")
    int updateMuted(@Param("roomId") String roomId, @Param("seatIndex") int seatIndex,
                    @Param("muted") boolean muted);
}
