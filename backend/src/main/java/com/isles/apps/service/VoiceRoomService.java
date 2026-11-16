package com.isles.apps.service;

import com.isles.apps.dto.VoiceRoomDtos;
import com.isles.apps.entity.ChannelApp;
import com.isles.apps.entity.VoiceRoom;
import com.isles.apps.entity.VoiceRoomSeat;
import com.isles.apps.mapper.ChannelAppMapper;
import com.isles.apps.mapper.VoiceRoomMapper;
import com.isles.apps.mapper.VoiceRoomSeatMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.time.ClockTimes;
import com.isles.realtime.RealtimePublisher;
import com.isles.realtime.dto.RealtimeEvent;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 语音房（V10 / docs/05 §2.14、docs/11 §5.6）：
 * 开房 / 进房 / 上麦 / 下麦 / 静音 / 关房，状态变更广播到 {@code /topic/voice/{roomId}/state}。
 *
 * <p>并发：上麦直接 INSERT 依赖数据库唯一键 {@code (room_id,seat_index)} 与 {@code (room_id,user_id)} 兜底，
 * 冲突转 409；不做「先查后插」，避免并发窗口。
 *
 * <p>在线成员：服务端只持久化「麦位」，不维护「谁在听」。听众列表由客户端信令（{@code /app/voice/.../signal}）
 * 自行维护，断线即消失——这样避免僵尸在线状态，也无需心跳清理任务。
 */
@Service
@RequiredArgsConstructor
public class VoiceRoomService {

    private static final Logger log = LoggerFactory.getLogger(VoiceRoomService.class);

    private static final int DEFAULT_MAX_SEATS = 8;
    private static final int MAX_ROOMS_PER_QUERY = 50;
    private static final String TOPIC_STATE_PREFIX = "/topic/voice/";

    private final VoiceRoomMapper roomMapper;
    private final VoiceRoomSeatMapper seatMapper;
    private final ChannelAppMapper appMapper;
    private final UserMapper userMapper;
    private final ChannelAccessService accessService;
    private final RealtimePublisher realtimePublisher;
    private final VoiceRoomPresence presence;

    // ------------------------------------------------------------------
    // 开房 / 查询
    // ------------------------------------------------------------------

    @Transactional
    public VoiceRoomDtos.RoomView create(String channelId, VoiceRoomDtos.CreateRoomRequest request, String userId) {
        accessService.requireMemberContext(channelId, userId);
        String appId = blankToNull(request.appId());
        if (appId != null) {
            ChannelApp app = appMapper.selectById(appId);
            if (app == null || !channelId.equals(app.getChannelId())) {
                throw new AppException(ErrorCode.CHANNEL_APP_NOT_FOUND);
            }
        }
        if (roomMapper.countOpenByHost(channelId, userId) > 0) {
            throw new AppException(ErrorCode.VOICE_ROOM_ALREADY_OPEN);
        }
        LocalDateTime now = ClockTimes.nowUtc();
        VoiceRoom room = new VoiceRoom();
        room.setId(UlidGenerator.generate());
        room.setChannelId(channelId);
        room.setAppId(appId);
        room.setHostId(userId);
        room.setTitle(request.title().trim());
        room.setStatus(VoiceRoom.Status.OPEN.name());
        room.setMaxSeats(request.maxSeats() == null ? DEFAULT_MAX_SEATS : request.maxSeats());
        room.setCreatedAt(now);
        roomMapper.insert(room);
        // 房主开房即在房：否则刚建好房连信令都订阅不了，前端必须多调一次 join
        presence.join(room.getId(), userId);
        log.info("语音房：开房 channelId={} roomId={} hostId={}", channelId, room.getId(), userId);
        VoiceRoomDtos.RoomView view = toView(room);
        broadcastState(room.getId(), "ROOM_OPENED", view);
        return view;
    }

    public List<VoiceRoomDtos.RoomView> listOpen(String channelId, String viewerId) {
        accessService.requireVisibleChannel(channelId, viewerId);
        List<VoiceRoom> rooms = roomMapper.selectOpenByChannel(channelId, MAX_ROOMS_PER_QUERY);
        return rooms.stream().map(this::toView).toList();
    }

    public VoiceRoomDtos.RoomView detail(String roomId, String viewerId) {
        VoiceRoom room = requireRoom(roomId);
        accessService.requireVisibleChannel(room.getChannelId(), viewerId);
        return toView(room);
    }

    /**
     * 进房：成员校验 + 登记「在房」（听众不落库，但在房登记必须做，
     * 否则 STOMP 信令无法判断连接主人是否真的在房间里，见 {@link VoiceRoomPresence}）。
     */
    public VoiceRoomDtos.RoomView join(String roomId, String userId) {
        VoiceRoom room = requireOpenRoom(roomId);
        accessService.requireMemberContext(room.getChannelId(), userId);
        presence.join(roomId, userId);
        return toView(room);
    }

    /**
     * 主动退房（听众离开）：清理在房登记。关房由 {@link #close} 整房清理。
     */
    public void leaveRoom(String roomId, String userId) {
        presence.leave(roomId, userId);
    }

    /**
     * STOMP 信令通道校验（订阅与发送两条路径都调用）：
     * <ol>
     *   <li>房间不存在或已关闭 → {@link ErrorCode#VOICE_ROOM_CLOSED}；</li>
     *   <li>既不在麦位上、也没有 join 登记 → {@link ErrorCode#VOICE_NOT_IN_ROOM}。</li>
     * </ol>
     *
     * <p>只查「麦位 + 进程内登记」，不查频道成员表：每帧都查库会打爆连接通道，
     * 而登记只在 join 成功（已校验成员）后写入，等价于成员校验。
     */
    public void requireSignalAccess(String roomId, String userId) {
        VoiceRoom room = roomId == null ? null : roomMapper.selectById(roomId);
        if (room == null || !VoiceRoom.Status.OPEN.name().equals(room.getStatus())) {
            throw new AppException(ErrorCode.VOICE_ROOM_CLOSED, "语音房不存在或已关闭");
        }
        if (presence.isPresent(roomId, userId)) {
            return;
        }
        if (seatMapper.selectByRoomAndUser(roomId, userId) != null) {
            // 上过麦但没走 join（例如直接调上麦接口）：补登记，后续帧不再查库
            presence.join(roomId, userId);
            return;
        }
        throw new AppException(ErrorCode.VOICE_NOT_IN_ROOM);
    }

    // ------------------------------------------------------------------
    // 麦位
    // ------------------------------------------------------------------

    @Transactional
    public VoiceRoomDtos.RoomView takeSeat(String roomId, int seatIndex, String userId) {
        VoiceRoom room = requireOpenRoom(roomId);
        accessService.requireMemberContext(room.getChannelId(), userId);
        presence.join(roomId, userId);
        int maxSeats = room.getMaxSeats() == null ? DEFAULT_MAX_SEATS : room.getMaxSeats();
        if (seatIndex < 0 || seatIndex >= maxSeats) {
            throw new AppException(ErrorCode.VOICE_SEAT_INVALID, "麦位序号需在 0-" + (maxSeats - 1) + " 之间");
        }
        VoiceRoomSeat existing = seatMapper.selectByRoomAndUser(roomId, userId);
        if (existing != null) {
            if (existing.getSeatIndex() != null && existing.getSeatIndex() == seatIndex) {
                return toView(room);
            }
            // 换麦：先释放原麦位，再抢新麦位
            seatMapper.deleteByRoomAndUser(roomId, userId);
        }
        VoiceRoomSeat seat = new VoiceRoomSeat();
        seat.setId(UlidGenerator.generate());
        seat.setRoomId(roomId);
        seat.setUserId(userId);
        seat.setSeatIndex(seatIndex);
        seat.setMuted(false);
        seat.setJoinedAt(ClockTimes.nowUtc());
        try {
            seatMapper.insert(seat);
        } catch (DuplicateKeyException ex) {
            // 唯一键兜底：并发抢同一麦位时后到者在这里失败
            throw new AppException(ErrorCode.VOICE_SEAT_OCCUPIED);
        }
        VoiceRoomDtos.RoomView view = toView(room);
        broadcastState(roomId, "SEAT_TAKEN", view);
        return view;
    }

    @Transactional
    public VoiceRoomDtos.RoomView leaveSeat(String roomId, String userId) {
        VoiceRoom room = requireOpenRoom(roomId);
        seatMapper.deleteByRoomAndUser(roomId, userId);
        VoiceRoomDtos.RoomView view = toView(room);
        broadcastState(roomId, "SEAT_LEFT", view);
        return view;
    }

    /**
     * 静音：房主可静音任何人；其他人只能静音自己。
     */
    @Transactional
    public VoiceRoomDtos.RoomView muteSeat(String roomId, int seatIndex, boolean muted, String userId) {
        VoiceRoom room = requireOpenRoom(roomId);
        VoiceRoomSeat seat = seatMapper.selectByRoom(roomId).stream()
                .filter(item -> item.getSeatIndex() != null && item.getSeatIndex() == seatIndex)
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.VOICE_SEAT_INVALID, "该麦位当前无人"));
        boolean host = userId.equals(room.getHostId());
        if (!host && !userId.equals(seat.getUserId())) {
            throw new AppException(ErrorCode.FORBIDDEN, "只有房主可以静音其他麦上成员");
        }
        seatMapper.updateMuted(roomId, seatIndex, muted);
        VoiceRoomDtos.RoomView view = toView(room);
        broadcastState(roomId, muted ? "SEAT_MUTED" : "SEAT_UNMUTED", view);
        return view;
    }

    // ------------------------------------------------------------------
    // 关房
    // ------------------------------------------------------------------

    @Transactional
    public VoiceRoomDtos.RoomView close(String roomId, String userId) {
        VoiceRoom room = requireRoom(roomId);
        if (!VoiceRoom.Status.OPEN.name().equals(room.getStatus())) {
            return toView(room);
        }
        boolean host = userId.equals(room.getHostId());
        if (!host) {
            // 非房主关房需要频道管理权限（例如房主掉线后由频道主收尾）
            accessService.requirePermission(room.getChannelId(), userId, ChannelPermissions.CHANNEL_SETTINGS);
        }
        room.setStatus(VoiceRoom.Status.CLOSED.name());
        room.setClosedAt(ClockTimes.nowUtc());
        roomMapper.updateById(room);
        int removed = seatMapper.deleteByRoom(roomId);
        presence.clearRoom(roomId);
        log.info("语音房：关房 roomId={} operator={} 清理麦位={}", roomId, userId, removed);
        VoiceRoomDtos.RoomView view = toView(room);
        broadcastState(roomId, "ROOM_CLOSED", view);
        return view;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private VoiceRoom requireRoom(String roomId) {
        VoiceRoom room = roomId == null ? null : roomMapper.selectById(roomId);
        if (room == null) {
            throw new AppException(ErrorCode.VOICE_ROOM_NOT_FOUND);
        }
        return room;
    }

    /**
     * 信令通道校验：房间存在且开启即返回（成员校验由调用方完成，避免重复查库）。
     */
    public VoiceRoom requireOpenRoomForSignal(String roomId) {
        return requireOpenRoom(roomId);
    }

    private VoiceRoom requireOpenRoom(String roomId) {
        VoiceRoom room = requireRoom(roomId);
        if (!VoiceRoom.Status.OPEN.name().equals(room.getStatus())) {
            throw new AppException(ErrorCode.VOICE_ROOM_CLOSED);
        }
        return room;
    }

    private VoiceRoomDtos.RoomView toView(VoiceRoom room) {
        List<VoiceRoomSeat> seats = seatMapper.selectByRoom(room.getId());
        Map<String, User> users = loadUsers(seats.stream().map(VoiceRoomSeat::getUserId).toList());
        List<VoiceRoomDtos.SeatView> seatViews = new ArrayList<>(seats.size());
        for (VoiceRoomSeat seat : seats) {
            User user = users.get(seat.getUserId());
            seatViews.add(new VoiceRoomDtos.SeatView(
                    seat.getSeatIndex() == null ? 0 : seat.getSeatIndex(),
                    seat.getUserId(),
                    user == null ? null : user.getNickname(),
                    user == null ? null : user.getAvatarUrl(),
                    Boolean.TRUE.equals(seat.getMuted()),
                    ClockTimes.toInstant(seat.getJoinedAt())));
        }
        User host = userMapper.selectById(room.getHostId());
        return new VoiceRoomDtos.RoomView(
                room.getId(),
                room.getChannelId(),
                room.getAppId(),
                room.getHostId(),
                host == null ? null : host.getNickname(),
                room.getTitle(),
                room.getStatus(),
                room.getMaxSeats() == null ? DEFAULT_MAX_SEATS : room.getMaxSeats(),
                seatViews,
                ClockTimes.toInstant(room.getCreatedAt()),
                ClockTimes.toInstant(room.getClosedAt()));
    }

    private Map<String, User> loadUsers(List<String> userIds) {
        List<String> ids = userIds.stream().filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    /**
     * 广播房间状态：{@code /topic/voice/{roomId}/state}，事件类型 {@code VOICE_STATE}。
     */
    private void broadcastState(String roomId, String event, VoiceRoomDtos.RoomView view) {
        Map<String, Object> data = new HashMap<>();
        data.put("event", event);
        data.put("room", view);
        try {
            realtimePublisher.publishTopic(TOPIC_STATE_PREFIX + roomId + "/state",
                    RealtimeEvent.of("VOICE_STATE", data));
        } catch (RuntimeException ex) {
            // 广播失败不影响 REST 结果
            log.warn("语音房状态广播失败 roomId={}", roomId, ex);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
