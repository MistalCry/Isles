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
 *   <li><b>频率</b>：平台日发帖总上限、人设日上限、发帖间隔 ≥ 2 小时；</li>
 *   <li><b>去重</b>：7 天内同话题不重复（{@link AgentMemoryService#isDuplicateTopic}）。</li>
 * </ol>
 *
 * <p>敏感词不在这里做——发帖/评论走的是现有 Service，三模式（REVIEW/MASK/BLOCK）已经在那边生效，
 * 重复校验会变成两套规则。
 */
@Component
@RequiredArgsConstructor
public class AgentGuard {

    private static final Logger log = LoggerFactory.getLogger(AgentGuard.class);

    /** 发帖最小间隔（docs/12 §5.1：≥ 2 小时）。 */
    static final Duration MIN_POST_INTERVAL = Duration.ofHours(2);

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
        LocalDateTime last = runMapper.lastOutputAt(profile.getUserId(), AgentRun.OutputType.POST.name());
        if (last != null && last.plus(MIN_POST_INTERVAL).isAfter(ClockTimes.nowUtc())) {
            return GuardResult.blocked("距上次发帖不足 " + MIN_POST_INTERVAL.toHours() + " 小时");
        }
        String topic = (title == null || title.isBlank()) ? content : title;
        if (memoryService.isDuplicateTopic(profile.getUserId(), topic)) {
            return GuardResult.blocked("话题与最近 7 天重复：" + safeTopic(topic));
        }
        return GuardResult.ok();
    }

    /**
     * 评论前检查：人设日上限。
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
        return GuardResult.ok();
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
