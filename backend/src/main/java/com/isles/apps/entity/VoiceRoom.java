package com.isles.apps.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 语音房（表 {@code voice_rooms}，V10）。
 *
 * <p>状态只有 {@code OPEN} / {@code CLOSED}；关房保留记录（{@code closedAt}），便于历史追溯。
 */
@Getter
@Setter
@TableName("voice_rooms")
public class VoiceRoom {

    @TableId(type = IdType.INPUT)
    private String id;
    private String channelId;
    private String appId;
    private String hostId;
    private String title;
    private String status;
    private Integer maxSeats;
    private LocalDateTime createdAt;
    private LocalDateTime closedAt;

    public enum Status {
        OPEN, CLOSED
    }
}
