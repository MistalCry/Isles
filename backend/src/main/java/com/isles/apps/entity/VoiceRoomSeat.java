package com.isles.apps.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 语音房麦位（表 {@code voice_room_seats}，V10）。
 *
 * <p>唯一键 {@code (room_id,user_id)} 与 {@code (room_id,seat_index)} 是抢座的数据库层兜底：
 * 并发上麦时只有一条 INSERT 成功，另一条抛 DuplicateKey 转 409。
 */
@Getter
@Setter
@TableName("voice_room_seats")
public class VoiceRoomSeat {

    @TableId(type = IdType.INPUT)
    private String id;
    private String roomId;
    private String userId;
    private Integer seatIndex;
    private Boolean muted;
    private LocalDateTime joinedAt;
}
