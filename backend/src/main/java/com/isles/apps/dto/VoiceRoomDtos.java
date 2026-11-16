package com.isles.apps.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 语音房接口模型（V10 / docs/05 §2.14）。
 */
public final class VoiceRoomDtos {

    private VoiceRoomDtos() {
    }

    public record SeatView(
            int seatIndex,
            String userId,
            String nickname,
            String avatarUrl,
            boolean muted,
            Instant joinedAt) {
    }

    public record RoomView(
            String id,
            String channelId,
            String appId,
            String hostId,
            String hostNickname,
            String title,
            String status,
            int maxSeats,
            List<SeatView> seats,
            Instant createdAt,
            Instant closedAt) {
    }

    public record CreateRoomRequest(
            String appId,

            @NotBlank(message = "房间标题不能为空")
            @Size(max = 60, message = "房间标题最长 60 个字符")
            String title,

            @Min(value = 1, message = "麦位数至少 1")
            @Max(value = 20, message = "麦位数最多 20")
            Integer maxSeats) {
    }

    public record TakeSeatRequest(
            @NotNull(message = "麦位序号不能为空")
            @Min(value = 0, message = "麦位序号从 0 开始")
            @Max(value = 19, message = "麦位序号最大 19")
            Integer seatIndex) {
    }

    public record MuteSeatRequest(
            @NotNull(message = "静音状态不能为空")
            Boolean muted) {
    }
}
