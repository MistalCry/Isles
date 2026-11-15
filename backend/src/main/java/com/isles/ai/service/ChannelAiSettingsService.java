package com.isles.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.ai.dto.AiRequests;
import com.isles.ai.dto.AiResponses;
import com.isles.ai.dto.AiTaskLogResponses;
import com.isles.ai.entity.AiTaskLog;
import com.isles.ai.entity.ChannelAiSetting;
import com.isles.ai.mapper.AiTaskLogMapper;
import com.isles.ai.mapper.ChannelAiSettingMapper;
import com.isles.channel.entity.Channel;
import com.isles.channel.entity.ChannelMember;
import com.isles.channel.entity.Role;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.mapper.ChannelMemberMapper;
import com.isles.channel.mapper.RoleMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.channel.service.SystemRoles;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.security.TokenHasher;
import com.isles.common.time.ClockTimes;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 频道助手设置与虚拟账号（docs/03 FR-MGR-08 / FR-ASSIST-01）。
 *
 * <p>虚拟账号复用 users 表：username 以 {@code aiassistant_} 为前缀标识（不新增 is_ai 列，遵守 V3 只加 platform_role 的约束），
 * 以「普通成员」身份加入频道，从而复用 PostService/CommentService 的权限校验发帖评论（docs/03 FR-ASSIST-03/04/05）。
 */
@Service
@RequiredArgsConstructor
public class ChannelAiSettingsService {

    private static final Logger log = LoggerFactory.getLogger(ChannelAiSettingsService.class);

    private static final String ASSISTANT_USERNAME_PREFIX = "aiassistant_";
    private static final String DEFAULT_NICKNAME = "频道助手";
    private static final int DEFAULT_SUMMARY_HOUR = 9;
    private static final int DEFAULT_AUTO_POST_PER_DAY = 1;
    private static final int DEFAULT_AUTO_COMMENT_PER_DAY = 3;
    private static final int DEFAULT_AUTO_LIKE_PER_DAY = 10;
    private static final int MAX_BIO_LENGTH = 500;
    private static final int MAX_USERNAME_ATTEMPTS = 5;

    private final ChannelAiSettingMapper settingMapper;
    private final AiTaskLogMapper taskLogMapper;
    private final ChannelMapper channelMapper;
    private final ChannelMemberMapper channelMemberMapper;
    private final RoleMapper roleMapper;
    private final UserMapper userMapper;
    private final ChannelAccessService accessService;
    private final PasswordEncoder passwordEncoder;

    // ---------------------------------------------------------------------
    // 查询
    // ---------------------------------------------------------------------

    public ChannelAiSetting findSetting(String channelId) {
        return settingMapper.selectOne(Wrappers.<ChannelAiSetting>lambdaQuery()
                .eq(ChannelAiSetting::getChannelId, channelId)
                .last("LIMIT 1"));
    }

    /**
     * 频道助手对外信息（频道页「频道助手」面板使用）：任何可见该频道的登录用户都可读取。
     */
    public AiResponses.AssistantInfoResponse getInfo(String channelId, String viewerId) {
        accessService.requireVisibleChannel(channelId, viewerId);
        ChannelAiSetting setting = findSetting(channelId);
        if (setting == null || setting.getAssistantUserId() == null) {
            return new AiResponses.AssistantInfoResponse(channelId, false, false, null,
                    DEFAULT_NICKNAME, null, null, null);
        }
        return new AiResponses.AssistantInfoResponse(channelId, true, Boolean.TRUE.equals(setting.getEnabled()),
                setting.getAssistantUserId(), setting.getNickname(), setting.getAvatarUrl(),
                setting.getBio(), setting.getPersona());
    }

    /**
     * 管理端读取频道助手设置（需要 channel.ai.manage 权限）。
     */
    public AiResponses.AssistantSettingsResponse getSettings(String channelId, String userId) {
        accessService.requirePermission(channelId, userId, ChannelPermissions.AI_MANAGE);
        ChannelAiSetting setting = findSetting(channelId);
        return toSettingsResponse(channelId, setting);
    }

    /**
     * 管理端保存频道助手设置（persona + 任务开关/频率）；同步更新虚拟账号昵称/头像/简介。
     */
    @Transactional
    public AiResponses.AssistantSettingsResponse saveSettings(String channelId, AiRequests.AssistantSettingsRequest request,
                                                              String userId) {
        accessService.requirePermission(channelId, userId, ChannelPermissions.AI_MANAGE);
        LocalDateTime now = ClockTimes.nowUtc();
        ChannelAiSetting setting = findSetting(channelId);
        boolean created = false;
        if (setting == null) {
            setting = new ChannelAiSetting();
            setting.setId(UlidGenerator.generate());
            setting.setChannelId(channelId);
            setting.setNickname(DEFAULT_NICKNAME);
            setting.setEnabled(false);
            setting.setCreatedAt(now);
            setting.setAutoPostPerDay(DEFAULT_AUTO_POST_PER_DAY);
            setting.setAutoCommentPerDay(DEFAULT_AUTO_COMMENT_PER_DAY);
            setting.setAutoLikePerDay(DEFAULT_AUTO_LIKE_PER_DAY);
            setting.setDailySummaryHour(DEFAULT_SUMMARY_HOUR);
            created = true;
        }
        setting.setNickname(normalizeNickname(request.nickname()));
        setting.setAvatarUrl(blankToNull(request.avatarUrl()));
        setting.setCoverUrl(blankToNull(request.coverUrl()));
        setting.setBio(blankToNull(request.bio()));
        setting.setPersona(blankToNull(request.persona()));
        setting.setEnabled(request.enabled());
        setting.setSummaryDailyEnabled(request.summaryDailyEnabled());
        setting.setSummaryWeeklyEnabled(request.summaryWeeklyEnabled());
        setting.setMonthlyContributorsEnabled(request.monthlyContributorsEnabled());
        setting.setAutoPostEnabled(request.autoPostEnabled());
        setting.setAutoCommentEnabled(request.autoCommentEnabled());
        setting.setAutoLikeEnabled(request.autoLikeEnabled());
        setting.setAutoReplyEnabled(request.autoReplyEnabled());
        setting.setDailySummaryHour(request.dailySummaryHour() == null
                ? DEFAULT_SUMMARY_HOUR
                : request.dailySummaryHour());
        setting.setAutoPostPerDay(request.autoPostPerDay() == null
                ? DEFAULT_AUTO_POST_PER_DAY
                : request.autoPostPerDay());
        setting.setAutoCommentPerDay(request.autoCommentPerDay() == null
                ? DEFAULT_AUTO_COMMENT_PER_DAY
                : request.autoCommentPerDay());
        setting.setAutoLikePerDay(request.autoLikePerDay() == null
                ? DEFAULT_AUTO_LIKE_PER_DAY
                : request.autoLikePerDay());
        setting.setUpdatedAt(now);
        if (created) {
            settingMapper.insert(setting);
        } else {
            settingMapper.updateById(setting);
        }
        syncAssistantUser(setting);
        log.info("频道助手设置已更新 channelId={} enabled={}", channelId, setting.getEnabled());
        return toSettingsResponse(channelId, setting);
    }

    // ---------------------------------------------------------------------
    // 虚拟账号
    // ---------------------------------------------------------------------

    /**
     * 管理端创建/修复助手虚拟账号（需要 channel.ai.manage）。
     */
    @Transactional
    public AiResponses.AssistantInfoResponse ensureAssistantAccount(String channelId, String userId) {
        accessService.requirePermission(channelId, userId, ChannelPermissions.AI_MANAGE);
        ensureAssistantAccountInternal(channelId);
        return getInfo(channelId, userId);
    }

    /**
     * 内部创建/复用助手虚拟账号（自动任务调度使用；频道设置已由有权限的管理者保存过）。
     *
     * @return 助手用户 id
     */
    @Transactional
    public String ensureAssistantAccountInternal(String channelId) {
        ChannelAiSetting setting = findSetting(channelId);
        if (setting == null) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "频道助手尚未配置");
        }
        Channel channel = accessService.requireActiveChannel(channelId);
        String assistantUserId = setting.getAssistantUserId();
        if (assistantUserId != null) {
            User existing = userMapper.selectById(assistantUserId);
            ChannelMember member = accessService.findMember(channelId, assistantUserId);
            if (existing != null && existing.getDeletedAt() == null
                    && User.Status.ACTIVE.name().equals(existing.getStatus())
                    && ChannelAccessService.isActiveMember(member)) {
                return assistantUserId;
            }
        }
        LocalDateTime now = ClockTimes.nowUtc();
        User assistant = assistantUserId == null ? null : userMapper.selectById(assistantUserId);
        if (assistant == null || assistant.getDeletedAt() != null) {
            assistant = createAssistantUser(setting, now);
            setting.setAssistantUserId(assistant.getId());
            setting.setUpdatedAt(now);
            settingMapper.updateById(setting);
        } else {
            assistant.setStatus(User.Status.ACTIVE.name());
            assistant.setUpdatedAt(now);
            userMapper.updateById(assistant);
        }
        ensureMembership(channel, assistant.getId(), now);
        log.info("频道助手虚拟账号就绪 channelId={} assistantUserId={}", channelId, assistant.getId());
        return assistant.getId();
    }

    /**
     * 任务日志分页（管理端）。
     */
    public AiResponses.TaskLogPage listTaskLogs(String channelId, String userId, String taskType, Integer page,
                                                Integer size) {
        accessService.requirePermission(channelId, userId, ChannelPermissions.AI_MANAGE);
        int pageSize = size == null || size <= 0 ? 20 : Math.min(size, 100);
        int pageNo = page == null || page <= 0 ? 1 : page;
        int offset = (pageNo - 1) * pageSize;
        String type = blankToNull(taskType);
        List<AiTaskLog> rows = taskLogMapper.selectPage(channelId, type, pageSize, offset);
        long total = taskLogMapper.countPage(channelId, type);
        return new AiResponses.TaskLogPage(rows.stream().map(this::toTaskLogResponse).toList(), total, pageNo, pageSize);
    }

    public AiResponses.TaskLogResponse toTaskLogResponse(AiTaskLog taskLog) {
        return AiTaskLogResponses.of(taskLog);
    }

    // ---------------------------------------------------------------------
    // 内部方法
    // ---------------------------------------------------------------------

    private AiResponses.AssistantSettingsResponse toSettingsResponse(String channelId, ChannelAiSetting setting) {
        if (setting == null) {
            return new AiResponses.AssistantSettingsResponse(channelId, null, DEFAULT_NICKNAME, null, null, null,
                    null, false, false, false, false, false, false, false, false,
                    DEFAULT_SUMMARY_HOUR, DEFAULT_AUTO_POST_PER_DAY, DEFAULT_AUTO_COMMENT_PER_DAY,
                    DEFAULT_AUTO_LIKE_PER_DAY, null);
        }
        return new AiResponses.AssistantSettingsResponse(
                channelId,
                setting.getAssistantUserId(),
                setting.getNickname(),
                setting.getAvatarUrl(),
                setting.getCoverUrl(),
                setting.getBio(),
                setting.getPersona(),
                Boolean.TRUE.equals(setting.getEnabled()),
                Boolean.TRUE.equals(setting.getSummaryDailyEnabled()),
                Boolean.TRUE.equals(setting.getSummaryWeeklyEnabled()),
                Boolean.TRUE.equals(setting.getMonthlyContributorsEnabled()),
                Boolean.TRUE.equals(setting.getAutoPostEnabled()),
                Boolean.TRUE.equals(setting.getAutoCommentEnabled()),
                Boolean.TRUE.equals(setting.getAutoLikeEnabled()),
                Boolean.TRUE.equals(setting.getAutoReplyEnabled()),
                setting.getDailySummaryHour(),
                setting.getAutoPostPerDay(),
                setting.getAutoCommentPerDay(),
                setting.getAutoLikePerDay(),
                ClockTimes.toInstant(setting.getUpdatedAt()));
    }

    private User createAssistantUser(ChannelAiSetting setting, LocalDateTime now) {
        User user = new User();
        user.setId(UlidGenerator.generate());
        user.setUsername(generateAssistantUsername());
        // 虚拟账号不可登录：密码为随机值的 BCrypt，不对外暴露
        user.setPasswordHash(passwordEncoder.encode(TokenHasher.randomToken()));
        user.setNickname(normalizeNickname(setting.getNickname()));
        user.setAvatarUrl(setting.getAvatarUrl());
        user.setSignature(truncate(setting.getBio(), 200));
        user.setGender(User.Gender.UNKNOWN.name());
        user.setStatus(User.Status.ACTIVE.name());
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        return user;
    }

    private String generateAssistantUsername() {
        for (int attempt = 0; attempt < MAX_USERNAME_ATTEMPTS; attempt++) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toLowerCase(Locale.ROOT);
            String username = ASSISTANT_USERNAME_PREFIX + suffix;
            Long count = userMapper.selectCount(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
            if (count == null || count == 0) {
                return username;
            }
        }
        throw new AppException(ErrorCode.INTERNAL_ERROR, "助手账号创建失败，请重试");
    }

    private void ensureMembership(Channel channel, String assistantUserId, LocalDateTime now) {
        ChannelMember member = accessService.findMember(channel.getId(), assistantUserId);
        String memberRoleId = requireMemberRoleId(channel.getId());
        if (ChannelAccessService.isActiveMember(member)) {
            return;
        }
        if (member == null) {
            ChannelMember newMember = new ChannelMember();
            newMember.setId(UlidGenerator.generate());
            newMember.setChannelId(channel.getId());
            newMember.setUserId(assistantUserId);
            newMember.setRoleId(memberRoleId);
            newMember.setStatus(ChannelMember.Status.ACTIVE.name());
            newMember.setJoinedAt(now);
            newMember.setCreatedAt(now);
            newMember.setUpdatedAt(now);
            channelMemberMapper.insert(newMember);
            channelMapper.addMemberCount(channel.getId(), 1);
        } else {
            member.setRoleId(memberRoleId);
            member.setStatus(ChannelMember.Status.ACTIVE.name());
            member.setMutedUntil(null);
            member.setJoinedAt(now);
            member.setUpdatedAt(now);
            channelMemberMapper.updateById(member);
            channelMapper.addMemberCount(channel.getId(), 1);
        }
    }

    private String requireMemberRoleId(String channelId) {
        Role memberRole = roleMapper.selectOne(Wrappers.<Role>lambdaQuery()
                .eq(Role::getChannelId, channelId)
                .eq(Role::getName, SystemRoles.MEMBER)
                .last("LIMIT 1"));
        if (memberRole == null) {
            throw new AppException(ErrorCode.INTERNAL_ERROR, "频道系统身份组缺失");
        }
        return memberRole.getId();
    }

    private void syncAssistantUser(ChannelAiSetting setting) {
        if (setting.getAssistantUserId() == null) {
            return;
        }
        User assistant = userMapper.selectById(setting.getAssistantUserId());
        if (assistant == null) {
            return;
        }
        assistant.setNickname(normalizeNickname(setting.getNickname()));
        assistant.setAvatarUrl(setting.getAvatarUrl());
        assistant.setSignature(truncate(setting.getBio(), 200));
        assistant.setUpdatedAt(ClockTimes.nowUtc());
        userMapper.updateById(assistant);
    }

    private String normalizeNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return DEFAULT_NICKNAME;
        }
        String value = nickname.trim();
        return value.length() > 32 ? value.substring(0, 32) : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
