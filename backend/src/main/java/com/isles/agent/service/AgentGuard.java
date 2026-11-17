package com.isles.agent.service;

import com.isles.agent.entity.AgentProfile;
import com.isles.agent.entity.AgentRun;
import com.isles.agent.mapper.AgentRunMapper;
import com.isles.common.time.ClockTimes;
import com.isles.platform.setting.PlatformSettingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 审核与频率闸门（V13 / docs/12 §2.1「审核闸门」、§5.1 节奏、§6 安全）。
 *
 * <p>四道关：
 * <ol>
 *   <li><b>kill switch</b>：{@code agent.enabled=false} 立即停止一切代理行为（docs/12 G5）；</li>
 *   <li><b>成本</b>：全平台当日 token 超过 {@code agent.dailyTokenBudget} 停止挑选；</li>
 *   <li><b>频率</b>：平台日发帖总上限、人设日上限、发帖/评论间隔（分钟数由配置决定，0 = 不限）；</li>
 *   <li><b>去重</b>：7 天内同话题不重复（{@link AgentMemoryService#isDuplicateTopic}）。</li>
 * </ol>
 *
 * <p>间隔不写死：{@code agent.minPostIntervalMinutes}（默认 120）与
 * {@code agent.minCommentIntervalMinutes}（默认 10）每次检查都现读配置快照，
 * 后台改完立即生效（配置写入会主动失效快照），联调时设 0 即可连续发帖。
 *
 * <p>敏感词不在这里做——发帖/评论走的是现有 Service，三模式（REVIEW/MASK/BLOCK）已经在那边生效，
 * 重复校验会变成两套规则。
 */
@Component
@RequiredArgsConstructor
public class AgentGuard {

    private static final Logger log = LoggerFactory.getLogger(AgentGuard.class);

    /** 发帖最小间隔配置键（分钟，0 = 不限）。 */
    static final String SETTING_MIN_POST_INTERVAL = "agent.minPostIntervalMinutes";
    /** 评论最小间隔配置键（分钟，0 = 不限）。 */
    static final String SETTING_MIN_COMMENT_INTERVAL = "agent.minCommentIntervalMinutes";
    /** 配置缺失时的兜底（docs/12 §5.1：默认 ≥ 2 小时）。 */
    static final int DEFAULT_MIN_POST_INTERVAL_MINUTES = 120;
    static final int DEFAULT_MIN_COMMENT_INTERVAL_MINUTES = 10;

    private final PlatformSettingService settingService;
    private final AgentRunMapper runMapper;
    private final AgentMemoryService memoryService;

    /** 总开关（kill switch）。 */
    public boolean enabled() {
        return settingService.getBoolean("agent.enabled");
    }

    /**
     * 运行前检查：总开关 + 当日 token 预算。
     */
    public GuardResult checkRun() {
        if (!enabled()) {
            return GuardResult.blocked("AI 代理总开关已关闭（agent.enabled=false）");
        }
        long budget = settingService.getInt("agent.dailyTokenBudget");
        if (budget > 0) {
            long used = runMapper.sumTokensSince(todayStart());
            if (used >= budget) {
                log.warn("AI 代理 token 预算已用尽 used={} budget={}", used, budget);
                return GuardResult.blocked("当日 token 预算已用尽（" + used + "/" + budget + "）");
            }
        }
        return GuardResult.ok();
    }

    /**
     * 发帖前检查：平台日上限 → 人设日上限 → 间隔 → 话题去重。
     */
    public GuardResult checkPost(AgentProfile profile, String title, String content) {
        LocalDateTime since = todayStart();
        long platformLimit = settingService.getInt("agent.dailyPostLimit");
        if (platformLimit > 0
                && runMapper.countOutputSinceAll(AgentRun.OutputType.POST.name(), since) >= platformLimit) {
            return GuardResult.blocked("全平台当日发帖已达上限（" + platformLimit + "）");
        }
        int profileLimit = profile.getPostsPerDay() == null ? 0 : profile.getPostsPerDay();
        if (profileLimit <= 0) {
            return GuardResult.blocked("该代理未开放发帖（postsPerDay=0）");
        }
        long posted = runMapper.countOutputSince(profile.getUserId(), AgentRun.OutputType.POST.name(), since);
        if (posted >= profileLimit) {
            return GuardResult.blocked("该代理今日发帖已达上限（" + posted + "/" + profileLimit + "）");
        }
        Duration postInterval = minPostInterval();
        if (!postInterval.isZero()) {
            LocalDateTime last = runMapper.lastOutputAt(profile.getUserId(), AgentRun.OutputType.POST.name());
            if (last != null && last.plus(postInterval).isAfter(ClockTimes.nowUtc())) {
                return GuardResult.blocked("距上次发帖不足 " + humanInterval(postInterval));
            }
        }
        String topic = (title == null || title.isBlank()) ? content : title;
        if (memoryService.isDuplicateTopic(profile.getUserId(), topic)) {
            return GuardResult.blocked("话题与最近 7 天重复：" + safeTopic(topic));
        }
        return GuardResult.ok();
    }

    /**
     * 评论前检查：人设日上限 → 评论间隔。
     */
    public GuardResult checkComment(AgentProfile profile) {
        int limit = profile.getCommentsPerDay() == null ? 0 : profile.getCommentsPerDay();
        if (limit <= 0) {
            return GuardResult.blocked("该代理未开放评论（commentsPerDay=0）");
        }
        long posted = runMapper.countOutputSince(profile.getUserId(), AgentRun.OutputType.COMMENT.name(),
                todayStart());
        if (posted >= limit) {
            return GuardResult.blocked("该代理今日评论已达上限（" + posted + "/" + limit + "）");
        }
        Duration commentInterval = minCommentInterval();
        LocalDateTime last = commentInterval.isZero()
                ? null
                : runMapper.lastOutputAt(profile.getUserId(), AgentRun.OutputType.COMMENT.name());
        if (last != null && last.plus(commentInterval).isAfter(ClockTimes.nowUtc())) {
            return GuardResult.blocked("距上次评论不足 " + humanInterval(commentInterval));
        }
        return GuardResult.ok();
    }

    /** 发帖最小间隔；0 表示不限（返回 {@link Duration#ZERO}）。 */
    Duration minPostInterval() {
        return intervalOf(SETTING_MIN_POST_INTERVAL, DEFAULT_MIN_POST_INTERVAL_MINUTES);
    }

    /** 评论最小间隔；0 表示不限（返回 {@link Duration#ZERO}）。 */
    Duration minCommentInterval() {
        return intervalOf(SETTING_MIN_COMMENT_INTERVAL, DEFAULT_MIN_COMMENT_INTERVAL_MINUTES);
    }

    /**
     * 现读配置换算成间隔：每次检查都调用，后台改完立刻生效（配置服务写入时失效快照，不重启）。
     */
    private Duration intervalOf(String key, int fallbackMinutes) {
        int minutes;
        try {
            minutes = settingService.getInt(key);
        } catch (RuntimeException ex) {
            // 配置项被下线或值被改坏：用默认值，不能让整个代理链路挂掉
            log.warn("读取代理间隔配置失败，回落默认值 key={} fallback={}分钟", key, fallbackMinutes);
            minutes = fallbackMinutes;
        }
        return minutes <= 0 ? Duration.ZERO : Duration.ofMinutes(minutes);
    }

    /**
     * 间隔的人类可读文案（提示词与拦截原因共用，避免文案写死"2 小时"）。
     *
     * <p>整小时给「N 小时」，不足 1 小时给「N 分钟」，其它给「N 小时 M 分钟」。
     */
    static String humanInterval(Duration interval) {
        long minutes = interval.toMinutes();
        if (minutes <= 0) {
            return "0 分钟";
        }
        if (minutes % 60 == 0) {
            return (minutes / 60) + " 小时";
        }
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        return (minutes / 60) + " 小时 " + (minutes % 60) + " 分钟";
    }

    /**
     * 当前 UTC 日零点（所有日上限按 UTC 计，与库内 DATETIME(3) 一致）。
     */
    LocalDateTime todayStart() {
        return ClockTimes.nowUtc().toLocalDate().atStartOfDay();
    }

    private String safeTopic(String topic) {
        if (topic == null) {
            return "";
        }
        return topic.length() <= 30 ? topic : topic.substring(0, 30) + "…";
    }

    /** 闸门结论。 */
    public record GuardResult(boolean allowed, String reason) {

        /** 静态工厂不能与组件访问器同名（Java 记录限制），故用 ok/blocked。 */
        public static GuardResult ok() {
            return new GuardResult(true, null);
        }

        public static GuardResult blocked(String reason) {
            return new GuardResult(false, reason);
        }
    }
}
