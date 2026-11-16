package com.isles.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.isles.agent.dto.AgentDtos;
import com.isles.agent.entity.AgentProfile;
import com.isles.agent.entity.AgentRun;
import com.isles.agent.mapper.AgentProfileMapper;
import com.isles.agent.mapper.AgentRunMapper;
import com.isles.agent.tool.AgentToolbox;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.json.JsonSupport;
import com.isles.common.time.ClockTimes;
import com.isles.platform.audit.PlatformAuditActions;
import com.isles.platform.audit.PlatformAuditService;
import com.isles.platform.setting.PlatformSettingService;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 代理人设与账号管理（V13 / docs/12 §3、§7）。
 *
 * <p>代理就是一条 {@code users} 记录 + {@code is_agent=1} + 一条 {@code agent_profiles}，
 * 权限/内容/通知/审核全部复用现有链路，不另建账号模型（docs/12 §0）。
 *
 * <p>账号密码由系统随机生成且不返回：代理不需要登录，生成强随机密码只是为了防止被猜中登录。
 * 删除代理时账号置 {@code DISABLED}、内容保留（docs/12 §7）。
 */
@Service
@RequiredArgsConstructor
public class AgentProfileService {

    private static final Logger log = LoggerFactory.getLogger(AgentProfileService.class);

    private static final String USERNAME_PREFIX = "agent_";
    private static final int USERNAME_RANDOM_LENGTH = 8;
    private static final String TONE_DEFAULT = "casual";

    private final AgentProfileMapper profileMapper;
    private final AgentRunMapper runMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JsonSupport jsonSupport;
    private final PlatformAuditService auditService;
    private final PlatformSettingService settingService;

    private static final SecureRandom RANDOM = new SecureRandom();

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public List<AgentDtos.AgentView> list() {
        List<AgentProfile> profiles = profileMapper.selectList(
                new LambdaQueryWrapper<AgentProfile>().orderByDesc(AgentProfile::getLastRunAt));
        List<AgentDtos.AgentView> views = new ArrayList<>(profiles.size());
        for (AgentProfile profile : profiles) {
            views.add(toView(profile));
        }
        return views;
    }

    public AgentDtos.AgentView detail(String agentUserId) {
        return toView(requireProfile(agentUserId));
    }

    public AgentProfile requireProfile(String agentUserId) {
        AgentProfile profile = agentUserId == null ? null : profileMapper.selectByUserId(agentUserId);
        if (profile == null) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND, "AI 代理不存在");
        }
        return profile;
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    @Transactional
    public AgentDtos.AgentView create(AgentDtos.SaveAgentRequest request, String operatorId) {
        String username = resolveUsername(request.username());
        LocalDateTime now = ClockTimes.nowUtc();
        User user = new User();
        user.setId(UlidGenerator.generate());
        user.setUsername(username);
        // 代理不登录：随机 32 位密码，不返回、不记录
        user.setPasswordHash(passwordEncoder.encode(randomPassword()));
        user.setNickname(request.displayName().trim());
        user.setAvatarUrl(blankToNull(request.avatarUrl()));
        user.setGender(User.Gender.UNKNOWN.name());
        user.setStatus(User.Status.ACTIVE.name());
        user.setIsAgent(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);

        AgentProfile profile = new AgentProfile();
        profile.setId(UlidGenerator.generate());
        profile.setUserId(user.getId());
        applyEditableFields(profile, request);
        profile.setStatus(AgentProfile.Status.ACTIVE.name());
        profileMapper.insert(profile);

        auditService.record(operatorId, null, PlatformAuditActions.AGENT_CREATE, "AGENT", user.getId(),
                Map.of("username", username, "displayName", profile.getDisplayName()));
        log.info("AI 代理：创建 agentUserId={} username={}", user.getId(), username);
        return toView(profile);
    }

    @Transactional
    public AgentDtos.AgentView update(String agentUserId, AgentDtos.SaveAgentRequest request, String operatorId) {
        AgentProfile profile = requireProfile(agentUserId);
        applyEditableFields(profile, request);
        profileMapper.updateById(profile);

        User user = userMapper.selectById(agentUserId);
        if (user != null) {
            user.setNickname(request.displayName().trim());
            user.setAvatarUrl(blankToNull(request.avatarUrl()));
            user.setUpdatedAt(ClockTimes.nowUtc());
            userMapper.updateById(user);
            // updateById 默认忽略 null：清空头像必须显式 set null，否则保存后还是旧图
            if (request.avatarUrl() != null && blankToNull(request.avatarUrl()) == null) {
                userMapper.update(null, new UpdateWrapper<User>()
                        .eq("id", agentUserId)
                        .set("avatar_url", null));
            }
        }
        auditService.record(operatorId, null, PlatformAuditActions.AGENT_UPDATE, "AGENT", agentUserId,
                Map.of("displayName", profile.getDisplayName()));
        return toView(profile);
    }

    @Transactional
    public void delete(String agentUserId, String operatorId) {
        AgentProfile profile = requireProfile(agentUserId);
        profileMapper.deleteById(profile.getId());
        // 内容保留，账号停用（docs/12 §7）
        User user = userMapper.selectById(agentUserId);
        if (user != null) {
            user.setStatus(User.Status.DISABLED.name());
            user.setUpdatedAt(ClockTimes.nowUtc());
            userMapper.updateById(user);
        }
        auditService.record(operatorId, null, PlatformAuditActions.AGENT_DELETE, "AGENT", agentUserId, Map.of());
        log.info("AI 代理：删除 agentUserId={}", agentUserId);
    }

    @Transactional
    public AgentDtos.AgentView pause(String agentUserId, String operatorId) {
        return changeStatus(agentUserId, AgentProfile.Status.PAUSED, PlatformAuditActions.AGENT_PAUSE, operatorId);
    }

    @Transactional
    public AgentDtos.AgentView resume(String agentUserId, String operatorId) {
        return changeStatus(agentUserId, AgentProfile.Status.ACTIVE, PlatformAuditActions.AGENT_RESUME, operatorId);
    }

    private AgentDtos.AgentView changeStatus(String agentUserId, AgentProfile.Status status, String action,
                                             String operatorId) {
        AgentProfile profile = requireProfile(agentUserId);
        profile.setStatus(status.name());
        profileMapper.updateById(profile);
        auditService.record(operatorId, null, action, "AGENT", agentUserId, Map.of("status", status.name()));
        log.info("AI 代理：状态变更 agentUserId={} status={}", agentUserId, status);
        return toView(profile);
    }

    /**
     * 记录一次运行结束时间（调度器用来做「最久没跑的优先」）。
     */
    public void touchLastRun(String agentUserId) {
        AgentProfile profile = profileMapper.selectByUserId(agentUserId);
        if (profile != null) {
            profile.setLastRunAt(ClockTimes.nowUtc());
            profileMapper.updateById(profile);
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void applyEditableFields(AgentProfile profile, AgentDtos.SaveAgentRequest request) {
        profile.setDisplayName(request.displayName().trim());
        profile.setBio(blankToNull(request.bio()));
        profile.setInterests(toJson(request.interests()));
        profile.setTone(request.tone() == null ? TONE_DEFAULT : request.tone());
        profile.setActiveHours(toJson(request.activeHours()));
        profile.setPostsPerDay(request.postsPerDay() == null ? 2 : request.postsPerDay());
        profile.setCommentsPerDay(request.commentsPerDay() == null ? 8 : request.commentsPerDay());
        profile.setChannels(toJson(request.channels()));
        profile.setToolsEnabled(toJson(request.toolsEnabled() == null || request.toolsEnabled().isEmpty()
                ? List.copyOf(AgentToolbox.ALLOWED_ACTIONS)
                : request.toolsEnabled()));
        if (request.status() != null) {
            profile.setStatus(request.status());
        }
    }

    private String resolveUsername(String requested) {
        String candidate = requested == null || requested.isBlank()
                ? USERNAME_PREFIX + randomSuffix(USERNAME_RANDOM_LENGTH)
                : requested.trim();
        if (usernameTaken(candidate)) {
            if (requested != null && !requested.isBlank()) {
                throw new AppException(ErrorCode.USERNAME_TAKEN);
            }
            // 自动生成撞名（概率极低）：再生成一次即可
            candidate = USERNAME_PREFIX + randomSuffix(USERNAME_RANDOM_LENGTH + 4);
            if (usernameTaken(candidate)) {
                throw new AppException(ErrorCode.USERNAME_TAKEN);
            }
        }
        return candidate;
    }

    private boolean usernameTaken(String username) {
        return userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username)) > 0;
    }

    private String randomSuffix(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    private String randomPassword() {
        return randomSuffix(32);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return null;
        }
        return jsonSupport.toJson(value);
    }

    private List<String> parseStringList(String json) {
        List<String> values = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return values;
        }
        try {
            List<?> raw = jsonSupport.fromJson(json, List.class);
            for (Object item : raw) {
                if (item != null) {
                    values.add(String.valueOf(item));
                }
            }
        } catch (RuntimeException ex) {
            log.debug("解析字符串列表失败：{}", json);
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private List<List<Integer>> parseHourRanges(String json) {
        List<List<Integer>> ranges = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return ranges;
        }
        try {
            List<?> raw = jsonSupport.fromJson(json, List.class);
            for (Object item : raw) {
                if (item instanceof List<?> pair && pair.size() >= 2) {
                    List<Integer> range = new ArrayList<>(2);
                    range.add(asInt(pair.get(0)));
                    range.add(asInt(pair.get(1)));
                    ranges.add(range);
                }
            }
        } catch (RuntimeException ex) {
            log.debug("解析活跃时段失败：{}", json);
        }
        return ranges;
    }

    private int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private AgentDtos.AgentView toView(AgentProfile profile) {
        User user = userMapper.selectById(profile.getUserId());
        LocalDateTime since = ClockTimes.nowUtc().toLocalDate().atStartOfDay();
        long todayPosts = runMapper.countOutputSince(profile.getUserId(), AgentRun.OutputType.POST.name(), since);
        long todayComments = runMapper.countOutputSince(profile.getUserId(), AgentRun.OutputType.COMMENT.name(),
                since);
        List<AgentRun> latest = runMapper.selectPage(profile.getUserId(), 1, 0);
        return new AgentDtos.AgentView(
                profile.getId(),
                profile.getUserId(),
                user == null ? null : user.getUsername(),
                profile.getDisplayName(),
                profile.getBio(),
                parseStringList(profile.getInterests()),
                profile.getTone(),
                parseHourRanges(profile.getActiveHours()),
                profile.getPostsPerDay() == null ? 0 : profile.getPostsPerDay(),
                profile.getCommentsPerDay() == null ? 0 : profile.getCommentsPerDay(),
                parseStringList(profile.getChannels()),
                parseStringList(profile.getToolsEnabled()),
                profile.getStatus(),
                ClockTimes.toInstant(profile.getLastRunAt()),
                todayPosts,
                todayComments,
                latest.isEmpty() ? null : latest.get(0).getStatus(),
                user == null ? null : user.getAvatarUrl(),
                user == null ? null : user.getNickname());
    }

    /**
     * 平台级开关状态（后台列表顶部展示「已停用」用）。
     */
    public Map<String, Object> switches() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", settingService.getBoolean("agent.enabled"));
        values.put("dailyPostLimit", settingService.getInt("agent.dailyPostLimit"));
        values.put("dailyTokenBudget", settingService.getInt("agent.dailyTokenBudget"));
        return values;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
