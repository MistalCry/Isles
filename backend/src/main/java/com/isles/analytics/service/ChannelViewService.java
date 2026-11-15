package com.isles.analytics.service;

import com.isles.analytics.entity.ChannelViewLog;
import com.isles.channel.mapper.ChannelViewLogMapper;
import com.isles.common.id.UlidGenerator;
import com.isles.common.time.ClockTimes;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 频道访问记录：登录用户访问帖子详情/频道页时写一条明细，作为数据中心 UV/PV 数据源。
 *
 * <p>写入失败不影响主流程（浏览不被阻断）；游客浏览不记录（docs/03 §8.3 的等价单机实现）。
 */
@Service
@RequiredArgsConstructor
public class ChannelViewService {

    private static final Logger log = LoggerFactory.getLogger(ChannelViewService.class);

    private final ChannelViewLogMapper channelViewLogMapper;

    public void recordView(String channelId, String postId, String userId) {
        if (channelId == null || userId == null) {
            return;
        }
        try {
            ChannelViewLog viewLog = new ChannelViewLog();
            viewLog.setId(UlidGenerator.generate());
            viewLog.setChannelId(channelId);
            viewLog.setUserId(userId);
            viewLog.setPostId(postId);
            viewLog.setCreatedAt(ClockTimes.nowUtc());
            channelViewLogMapper.insert(viewLog);
        } catch (RuntimeException ex) {
            log.warn("频道访问记录写入失败 channelId={} userId={}", channelId, userId, ex);
        }
    }
}
