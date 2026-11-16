package com.isles.apps.service;

import com.isles.apps.dto.ActivityDtos;
import com.isles.apps.entity.ActivitySignup;
import com.isles.apps.entity.ChannelActivity;
import com.isles.apps.mapper.ActivitySignupMapper;
import com.isles.apps.mapper.ChannelActivityMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.time.ClockTimes;
import com.isles.notification.entity.Notification;
import com.isles.notification.service.NotificationService;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 频道活动（V10 / docs/05 §2.14）：
 * 草稿 → 发布 →（取消）；报名/取消报名；开始前 30 分钟站内提醒（幂等）。
 *
 * <p>权限：创建/编辑/发布/取消/查看报名名单需要 {@code channel.settings}；
 * 浏览只看已发布活动（管理员可见全部状态）；报名要求频道成员。
 */
@Service
@RequiredArgsConstructor
public class ActivityService {

    private static final Logger log = LoggerFactory.getLogger(ActivityService.class);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    /** 提醒提前量：开始前 30 分钟。 */
    private static final int REMINDER_MINUTES = 30;
    private static final int MAX_REMINDER_PER_SCAN = 100;

    private final ChannelActivityMapper activityMapper;
    private final ActivitySignupMapper signupMapper;
    private final ChannelAccessService accessService;
    private final NotificationService notificationService;
    private final UserMapper userMapper;

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public ActivityDtos.ActivityPage list(String channelId, String viewerId, String status, Integer page,
                                          Integer size) {
        accessService.requireVisibleChannel(channelId, viewerId);
        int pageNo = page == null || page <= 0 ? 1 : page;
        int pageSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        String statusFilter = blankToNull(status);
        if (statusFilter != null && !isKnownStatus(statusFilter)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "活动状态取值非法");
        }
        // 非管理员只能看已发布活动，避免草稿/取消的活动被围观
        if (!isManager(channelId, viewerId)) {
            statusFilter = ChannelActivity.Status.PUBLISHED.name();
        }
        long total = activityMapper.countByChannel(channelId, statusFilter);
        List<ChannelActivity> rows = activityMapper.selectByChannel(channelId, statusFilter, pageSize,
                (pageNo - 1) * pageSize);
        List<ActivityDtos.ActivityView> items = toViews(rows, viewerId);
        return new ActivityDtos.ActivityPage(items, total, pageNo, pageSize);
    }

    public ActivityDtos.ActivityView detail(String activityId, String viewerId) {
        ChannelActivity activity = require(activityId);
        accessService.requireVisibleChannel(activity.getChannelId(), viewerId);
        return toViews(List.of(activity), viewerId).get(0);
    }

    // ------------------------------------------------------------------
    // 管理
    // ------------------------------------------------------------------

    @Transactional
    public ActivityDtos.ActivityView create(String channelId, ActivityDtos.SaveActivityRequest request,
                                            String userId) {
        accessService.requirePermission(channelId, userId, ChannelPermissions.CHANNEL_SETTINGS);
        LocalDateTime now = ClockTimes.nowUtc();
        ChannelActivity activity = new ChannelActivity();
        activity.setId(UlidGenerator.generate());
        activity.setChannelId(channelId);
        applyEditableFields(activity, request);
        activity.setStatus(ChannelActivity.Status.DRAFT.name());
        activity.setCreatedBy(userId);
        activity.setCreatedAt(now);
        activity.setUpdatedAt(now);
        activityMapper.insert(activity);
        log.info("频道活动：创建 channelId={} activityId={}", channelId, activity.getId());
        return toViews(List.of(activity), userId).get(0);
    }

    @Transactional
    public ActivityDtos.ActivityView update(String activityId, ActivityDtos.SaveActivityRequest request,
                                            String userId) {
        ChannelActivity activity = require(activityId);
        accessService.requirePermission(activity.getChannelId(), userId, ChannelPermissions.CHANNEL_SETTINGS);
        if (ChannelActivity.Status.CANCELLED.name().equals(activity.getStatus())) {
            throw new AppException(ErrorCode.ACTIVITY_NOT_PUBLISHED, "已取消的活动不能编辑");
        }
        applyEditableFields(activity, request);
        activity.setUpdatedAt(ClockTimes.nowUtc());
        activityMapper.updateById(activity);
        return toViews(List.of(activity), userId).get(0);
    }

    @Transactional
    public void delete(String activityId, String userId) {
        ChannelActivity activity = require(activityId);
        accessService.requirePermission(activity.getChannelId(), userId, ChannelPermissions.CHANNEL_SETTINGS);
        if (ChannelActivity.Status.PUBLISHED.name().equals(activity.getStatus())) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "已发布的活动请先取消再删除");
        }
        activityMapper.deleteById(activity.getId());
        log.info("频道活动：删除 activityId={}", activityId);
    }

    @Transactional
    public ActivityDtos.ActivityView publish(String activityId, String userId) {
        ChannelActivity activity = require(activityId);
        accessService.requirePermission(activity.getChannelId(), userId, ChannelPermissions.CHANNEL_SETTINGS);
        if (ChannelActivity.Status.CANCELLED.name().equals(activity.getStatus())) {
            throw new AppException(ErrorCode.ACTIVITY_NOT_PUBLISHED, "已取消的活动不能重新发布");
        }
        activity.setStatus(ChannelActivity.Status.PUBLISHED.name());
        activity.setUpdatedAt(ClockTimes.nowUtc());
        activityMapper.updateById(activity);
        log.info("频道活动：发布 activityId={}", activityId);
        return toViews(List.of(activity), userId).get(0);
    }

    @Transactional
    public ActivityDtos.ActivityView cancel(String activityId, String userId) {
        ChannelActivity activity = require(activityId);
        accessService.requirePermission(activity.getChannelId(), userId, ChannelPermissions.CHANNEL_SETTINGS);
        activity.setStatus(ChannelActivity.Status.CANCELLED.name());
        activity.setUpdatedAt(ClockTimes.nowUtc());
        activityMapper.updateById(activity);
        log.info("频道活动：取消 activityId={}", activityId);
        return toViews(List.of(activity), userId).get(0);
    }

    // ------------------------------------------------------------------
    // 报名
    // ------------------------------------------------------------------

    @Transactional
    public ActivityDtos.ActivityView signup(String activityId, String userId) {
        ChannelActivity activity = requireSignable(activityId);
        accessService.requireMemberContext(activity.getChannelId(), userId);
        ActivitySignup existing = signupMapper.selectByActivityAndUser(activityId, userId);
        if (existing != null && ActivitySignup.Status.GOING.name().equals(existing.getStatus())) {
            return toViews(List.of(activity), userId).get(0);
        }
        if (activity.getCapacity() != null && signupMapper.countGoing(activityId) >= activity.getCapacity()) {
            throw new AppException(ErrorCode.ACTIVITY_FULL);
        }
        if (existing == null) {
            ActivitySignup signup = new ActivitySignup();
            signup.setId(UlidGenerator.generate());
            signup.setActivityId(activityId);
            signup.setUserId(userId);
            signup.setStatus(ActivitySignup.Status.GOING.name());
            signup.setCreatedAt(ClockTimes.nowUtc());
            signupMapper.insert(signup);
        } else {
            existing.setStatus(ActivitySignup.Status.GOING.name());
            signupMapper.updateById(existing);
        }
        return toViews(List.of(activity), userId).get(0);
    }

    @Transactional
    public ActivityDtos.ActivityView cancelSignup(String activityId, String userId) {
        ChannelActivity activity = require(activityId);
        ActivitySignup existing = signupMapper.selectByActivityAndUser(activityId, userId);
        if (existing != null && ActivitySignup.Status.GOING.name().equals(existing.getStatus())) {
            existing.setStatus(ActivitySignup.Status.CANCELLED.name());
            signupMapper.updateById(existing);
        }
        return toViews(List.of(activity), userId).get(0);
    }

    /**
     * 报名名单（管理端）：昵称/头像/状态，按报名时间升序。
     */
    public ActivityDtos.SignupList signups(String activityId, String userId) {
        ChannelActivity activity = require(activityId);
        accessService.requirePermission(activity.getChannelId(), userId, ChannelPermissions.CHANNEL_SETTINGS);
        List<ActivitySignup> rows = signupMapper.selectGoing(activityId);
        List<String> userIds = rows.stream().map(ActivitySignup::getUserId).distinct().toList();
        Map<String, User> users = userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));
        List<ActivityDtos.SignupView> items = new ArrayList<>(rows.size());
        for (ActivitySignup row : rows) {
            User user = users.get(row.getUserId());
            items.add(new ActivityDtos.SignupView(row.getUserId(),
                    user == null ? null : user.getNickname(),
                    user == null ? null : user.getAvatarUrl(),
                    row.getStatus(),
                    ClockTimes.toInstant(row.getCreatedAt())));
        }
        return new ActivityDtos.SignupList(items, items.size());
    }

    // ------------------------------------------------------------------
    // 提醒
    // ------------------------------------------------------------------

    /**
     * 扫描「开始前 30 分钟内」的已发布活动，给报名者发站内提醒。
     *
     * <p>幂等：先用 {@code claimReminder} 抢占 {@code reminder_sent_at}，抢到才发通知；
     * 多实例/重跑不会重复提醒。
     *
     * @return 本次发出提醒的活动数
     */
    @Transactional
    public int remindDue() {
        LocalDateTime now = ClockTimes.nowUtc();
        List<ChannelActivity> due = activityMapper.selectDueReminders(now, now.plusMinutes(REMINDER_MINUTES),
                MAX_REMINDER_PER_SCAN);
        int reminded = 0;
        for (ChannelActivity activity : due) {
            if (activityMapper.claimReminder(activity.getId(), now) == 0) {
                continue;
            }
            List<ActivitySignup> signups = signupMapper.selectGoing(activity.getId());
            for (ActivitySignup signup : signups) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("activityId", activity.getId());
                payload.put("channelId", activity.getChannelId());
                payload.put("startAt", String.valueOf(activity.getStartAt()));
                notificationService.notifySystem(signup.getUserId(), Notification.Type.SYSTEM,
                        activity.getChannelId(), null, null,
                        "活动即将开始",
                        "你报名的活动《" + activity.getTitle() + "》将在 30 分钟内开始"
                                + (activity.getLocation() == null ? "" : "，地点：" + activity.getLocation()),
                        payload);
            }
            reminded++;
            log.info("频道活动：提醒已发送 activityId={} signups={}", activity.getId(), signups.size());
        }
        return reminded;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private ChannelActivity require(String activityId) {
        ChannelActivity activity = activityId == null ? null : activityMapper.selectById(activityId);
        if (activity == null) {
            throw new AppException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        return activity;
    }

    private ChannelActivity requireSignable(String activityId) {
        ChannelActivity activity = require(activityId);
        if (!ChannelActivity.Status.PUBLISHED.name().equals(activity.getStatus())) {
            throw new AppException(ErrorCode.ACTIVITY_NOT_PUBLISHED);
        }
        LocalDateTime now = ClockTimes.nowUtc();
        if (activity.getStartAt() != null && activity.getStartAt().isBefore(now)) {
            throw new AppException(ErrorCode.ACTIVITY_ENDED, "活动已开始，无法报名");
        }
        return activity;
    }

    private void applyEditableFields(ChannelActivity activity, ActivityDtos.SaveActivityRequest request) {
        LocalDateTime startAt = LocalDateTime.ofInstant(request.startAt(), ZoneOffset.UTC);
        LocalDateTime endAt = request.endAt() == null ? null : LocalDateTime.ofInstant(request.endAt(), ZoneOffset.UTC);
        if (endAt != null && endAt.isBefore(startAt)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "结束时间不能早于开始时间");
        }
        activity.setTitle(request.title().trim());
        activity.setDescription(blankToNull(request.description()));
        activity.setStartAt(startAt);
        activity.setEndAt(endAt);
        activity.setLocation(blankToNull(request.location()));
        activity.setCapacity(request.capacity());
        // 改了开始时间要允许重新提醒
        activity.setReminderSentAt(null);
    }

    private List<ActivityDtos.ActivityView> toViews(List<ChannelActivity> activities, String viewerId) {
        if (activities.isEmpty()) {
            return List.of();
        }
        List<String> ids = activities.stream().map(ChannelActivity::getId).toList();
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Boolean> signed = new LinkedHashMap<>();
        for (String id : ids) {
            counts.put(id, signupMapper.countGoing(id));
            ActivitySignup mine = viewerId == null ? null : signupMapper.selectByActivityAndUser(id, viewerId);
            signed.put(id, mine != null && ActivitySignup.Status.GOING.name().equals(mine.getStatus()));
        }
        List<ActivityDtos.ActivityView> views = new ArrayList<>(activities.size());
        for (ChannelActivity activity : activities) {
            views.add(new ActivityDtos.ActivityView(
                    activity.getId(),
                    activity.getChannelId(),
                    activity.getTitle(),
                    activity.getDescription(),
                    ClockTimes.toInstant(activity.getStartAt()),
                    ClockTimes.toInstant(activity.getEndAt()),
                    activity.getLocation(),
                    activity.getCapacity(),
                    activity.getStatus(),
                    counts.getOrDefault(activity.getId(), 0L),
                    signed.getOrDefault(activity.getId(), false),
                    ClockTimes.toInstant(activity.getCreatedAt())));
        }
        return views;
    }

    private boolean isManager(String channelId, String userId) {
        if (userId == null) {
            return false;
        }
        return accessService.permissionsOf(channelId, userId).contains(ChannelPermissions.CHANNEL_SETTINGS);
    }

    private boolean isKnownStatus(String status) {
        return ChannelActivity.Status.DRAFT.name().equals(status)
                || ChannelActivity.Status.PUBLISHED.name().equals(status)
                || ChannelActivity.Status.CANCELLED.name().equals(status);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
