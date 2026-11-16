package com.isles.apps.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.isles.apps.entity.ActivitySignup;
import com.isles.apps.entity.ChannelActivity;
import com.isles.apps.entity.ChannelApp;
import com.isles.apps.entity.VoiceRoom;
import com.isles.apps.entity.VoiceRoomSeat;
import com.isles.apps.mapper.ActivitySignupMapper;
import com.isles.apps.mapper.ChannelActivityMapper;
import com.isles.apps.mapper.ChannelAppMapper;
import com.isles.apps.mapper.VoiceRoomMapper;
import com.isles.apps.mapper.VoiceRoomSeatMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 频道级数据清理（K2）：频道解散时把 V10 引入的应用 / 语音房 / 活动一并删除。
 *
 * <p>为什么必须走服务层而不是只靠外键：解散是**软删除**（{@code channels.status=DISSOLVED}），
 * 频道行还在，{@code ON DELETE CASCADE} 不会触发。V12 里的 CASCADE 只是为将来的物理删除兜底。
 *
 * <p>删除顺序按依赖从内到外：报名 → 活动 → 麦位 → 房间 → 应用，避免外键约束报错。
 */
@Service
@RequiredArgsConstructor
public class ChannelAppDataCleaner {

    private static final Logger log = LoggerFactory.getLogger(ChannelAppDataCleaner.class);

    private final ChannelAppMapper appMapper;
    private final VoiceRoomMapper roomMapper;
    private final VoiceRoomSeatMapper seatMapper;
    private final ChannelActivityMapper activityMapper;
    private final ActivitySignupMapper signupMapper;
    private final VoiceRoomPresence presence;

    /**
     * 清理某频道的全部应用数据。
     *
     * @return 删除统计，便于审计日志与排查
     */
    @Transactional
    public PurgeResult purgeChannel(String channelId) {
        if (channelId == null) {
            return new PurgeResult(0, 0, 0, 0, 0);
        }
        List<String> activityIds = activityMapper.selectList(
                        new LambdaQueryWrapper<ChannelActivity>().eq(ChannelActivity::getChannelId, channelId))
                .stream().map(ChannelActivity::getId).toList();
        int signups = 0;
        if (!activityIds.isEmpty()) {
            signups = signupMapper.delete(new LambdaQueryWrapper<ActivitySignup>()
                    .in(ActivitySignup::getActivityId, activityIds));
        }
        int activities = activityMapper.delete(
                new LambdaQueryWrapper<ChannelActivity>().eq(ChannelActivity::getChannelId, channelId));

        List<String> roomIds = roomMapper.selectList(
                        new LambdaQueryWrapper<VoiceRoom>().eq(VoiceRoom::getChannelId, channelId))
                .stream().map(VoiceRoom::getId).toList();
        int seats = 0;
        for (String roomId : roomIds) {
            seats += seatMapper.delete(new LambdaQueryWrapper<VoiceRoomSeat>().eq(VoiceRoomSeat::getRoomId, roomId));
            // 在房登记是进程内状态，删库不会连带清掉，必须显式清
            presence.clearRoom(roomId);
        }
        int rooms = roomMapper.delete(
                new LambdaQueryWrapper<VoiceRoom>().eq(VoiceRoom::getChannelId, channelId));
        int apps = appMapper.delete(
                new LambdaQueryWrapper<ChannelApp>().eq(ChannelApp::getChannelId, channelId));

        log.info("频道数据清理完成 channelId={} apps={} rooms={} seats={} activities={} signups={}",
                channelId, apps, rooms, seats, activities, signups);
        return new PurgeResult(apps, rooms, seats, activities, signups);
    }

    /** 清理统计。 */
    public record PurgeResult(int apps, int rooms, int seats, int activities, int signups) {
    }
}
