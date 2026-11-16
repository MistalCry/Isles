package com.isles.agent.service;

import com.isles.agent.dto.AgentDtos;
import com.isles.agent.entity.AgentProfile;
import com.isles.agent.entity.AgentRun;
import com.isles.agent.mapper.AgentProfileMapper;
import com.isles.agent.mapper.AgentRunMapper;
import com.isles.agent.tool.AgentToolbox;
import com.isles.ai.entity.AiModelConfig;
import com.isles.ai.service.AiModelConfigService;
import com.isles.channel.entity.Channel;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.json.JsonSupport;
import com.isles.common.time.ClockTimes;
import com.isles.platform.audit.PlatformAuditActions;
import com.isles.platform.audit.PlatformAuditService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 单次 tick 的编排（V13 / docs/12 §2.1）：
 * 调度挑选 → 读人设与记忆 → 计划 → 执行工具 → 审核闸门 → 落库与日志 → 更新记忆。
 *
 * <p>降级要求（本轮硬性）：
 * <ul>
 *   <li>没有可用模型 → 记 {@code SKIPPED}，**不抛异常、不刷 ERROR 日志**（debug 级别一行）；</li>
 *   <li>动作越界 / 触发限额 / 敏感词拦截 → 记 {@code GUARDED}，理由落 {@code error} 列；</li>
 *   <li>工具执行异常 → 记 {@code FAILED}，同样不向上抛（调度器不能因为一个代理挂掉整轮）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    /** 每次 tick 的行动概率（docs/12 §5.1：概率性行动，不是每次 tick 都产出）。 */
    private static final double ACTION_PROBABILITY = 0.3;
    /** 提示词里带多少条最近话题记忆。 */
    private static final int MEMORY_PROMPT_LIMIT = 10;
    /** 未配置频道时自动加入的公开频道上限（否则代理无频道可活动）。 */
    private static final int AUTO_JOIN_CHANNELS = 3;

    private final AgentProfileMapper profileMapper;
    private final AgentRunMapper runMapper;
    private final AgentToolbox toolbox;
    private final AgentPlanner planner;
    private final AgentGuard guard;
    private final AgentMemoryService memoryService;
    private final AgentProfileService profileService;
    private final AiModelConfigService modelConfigService;
    private final ChannelMapper channelMapper;
    private final ChannelService channelService;
    private final ChannelAccessService accessService;
    private final JsonSupport jsonSupport;
    private final PlatformAuditService auditService;

    // ------------------------------------------------------------------
    // 调度
    // ------------------------------------------------------------------

    /**
     * 调度 tick：挑选「该醒的」代理并逐个执行。
     *
     * <p>kill switch 关闭时直接返回（连查询都不做），保证 5 秒内生效。
     */
    public TickSummary tick() {
        if (!guard.enabled()) {
            return new TickSummary(0, 0, 0, true);
        }
        List<AgentProfile> profiles = profileMapper.selectActive();
        int selected = 0;
        int ran = 0;
        int skipped = 0;
        for (AgentProfile profile : profiles) {
            if (!shouldWake(profile, ClockTimes.nowUtc())) {
                continue;
            }
            selected++;
            AgentRun run = runOne(profile, false);
            if (run == null) {
                skipped++;
            } else if (AgentRun.Status.SKIPPED.name().equals(run.getStatus())) {
                skipped++;
            } else {
                ran++;
            }
        }
        if (selected > 0) {
            log.info("AI 代理 tick 完成 selected={} ran={} skipped={}", selected, ran, skipped);
        }
        return new TickSummary(selected, ran, skipped, false);
    }

    /**
     * 手动触发（后台 run-now）：忽略活跃时段与概率，其余闸门照旧。
     */
    public AgentDtos.RunNowResponse runNow(String agentUserId, String operatorId) {
        AgentProfile profile = profileService.requireProfile(agentUserId);
        if (!guard.enabled()) {
            // 手动触发也尊重 kill switch：只回原因，不写日志行，避免后台被 SKIPPED 淹没
            return new AgentDtos.RunNowResponse(AgentRun.Status.SKIPPED.name(),
                    "AI 代理总开关已关闭（agent.enabled=false）", null);
        }
        AgentRun run = runOne(profile, true);
        auditService.record(operatorId, null, PlatformAuditActions.AGENT_RUN, "AGENT", agentUserId,
                Map.of("status", run == null ? "SKIPPED" : run.getStatus()));
        return new AgentDtos.RunNowResponse(run == null ? AgentRun.Status.SKIPPED.name() : run.getStatus(),
                run == null ? "未执行" : run.getError(), run == null ? null : toView(run));
    }

    /**
     * 是否唤醒：人设 ACTIVE + 处于活跃时段 + 概率命中。
     */
    boolean shouldWake(AgentProfile profile, LocalDateTime now) {
        if (!AgentProfile.Status.ACTIVE.name().equals(profile.getStatus())) {
            return false;
        }
        if (!inActiveHours(profile.getActiveHours(), now.getHour())) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < ACTION_PROBABILITY;
    }

    /**
     * 活跃时段判定：JSON 形如 [[20,24],[0,1]]；为空表示全天可活跃。
     */
    boolean inActiveHours(String activeHoursJson, int hour) {
        if (activeHoursJson == null || activeHoursJson.isBlank()) {
            return true;
        }
        List<List<Integer>> ranges = parseRanges(activeHoursJson);
        if (ranges.isEmpty()) {
            return true;
        }
        for (List<Integer> range : ranges) {
            if (range.size() < 2) {
                continue;
            }
            int start = range.get(0);
            int end = range.get(1);
            if (start <= end ? (hour >= start && hour < end) : (hour >= start || hour < end)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 单次运行
    // ------------------------------------------------------------------

    /**
     * 执行一次完整流程。
     *
     * @param manual 手动触发（跳过概率与时段）
     * @return 已落库的运行日志；kill switch 关闭时返回 null
     */
    public AgentRun runOne(AgentProfile profile, boolean manual) {
        LocalDateTime startedAt = ClockTimes.nowUtc();
        AgentRun run = new AgentRun();
        run.setId(UlidGenerator.generate());
        run.setAgentId(profile.getUserId());
        run.setStartedAt(startedAt);
        run.setStatus(AgentRun.Status.SKIPPED.name());
        run.setOutputType(AgentRun.OutputType.NONE.name());
        run.setTokensIn(0);
        run.setTokensOut(0);

        try {
            AgentGuard.GuardResult runGuard = guard.checkRun();
            if (!runGuard.allowed()) {
                return finish(run, AgentRun.Status.SKIPPED, null, null, runGuard.reason(), null, 0, 0);
            }
            AiModelConfig model = modelConfigService.resolveDefault();
            if (model == null) {
                // 降级：没有可用模型时只记 SKIPPED，debug 级别，不刷 ERROR
                log.debug("AI 代理无可用模型，跳过 agentId={}", profile.getUserId());
                return finish(run, AgentRun.Status.SKIPPED, null, null, "未配置可用的 AI 模型", null, 0, 0);
            }

            List<String> channelIds = resolveChannels(profile);
            List<AgentPlanner.ChannelBrief> briefs = toBriefs(channelIds);
            List<com.isles.agent.entity.AgentMemory> memories = memoryService.recentTopics(
                    profile.getUserId(), MEMORY_PROMPT_LIMIT);

            AgentPlanner.AgentPlan plan;
            try {
                plan = planner.plan(model, profile, memories, briefs, busyHint(profile, channelIds.isEmpty()));
            } catch (AppException ex) {
                return finish(run, AgentRun.Status.FAILED, null, null, ex.getMessage(), null, 0, 0);
            } catch (RuntimeException ex) {
                log.warn("AI 代理计划阶段失败 agentId={}", profile.getUserId(), ex);
                return finish(run, AgentRun.Status.FAILED, null, null, "模型调用失败：" + ex.getMessage(), null, 0, 0);
            }
            run.setPlanJson(plan.raw());
            run.setTokensIn(plan.tokensIn());
            run.setTokensOut(plan.tokensOut());

            if (!AgentToolbox.ALLOWED_ACTIONS.contains(plan.action())) {
                return finish(run, AgentRun.Status.GUARDED, null, null,
                        "动作不在白名单：" + plan.action(), null, plan.tokensIn(), plan.tokensOut());
            }
            AgentGuard.GuardResult actionGuard = checkAction(profile, plan);
            if (!actionGuard.allowed()) {
                return finish(run, AgentRun.Status.GUARDED, null, null, actionGuard.reason(), null,
                        plan.tokensIn(), plan.tokensOut());
            }

            AgentToolbox.AgentContext context = new AgentToolbox.AgentContext(profile.getUserId(), profile,
                    channelIds);
            AgentToolbox.ToolOutcome outcome;
            try {
                outcome = toolbox.execute(plan.action(), plan.args(), context);
            } catch (AppException ex) {
                AgentRun.Status status = isGuardRejection(ex.getErrorCode())
                        ? AgentRun.Status.GUARDED
                        : AgentRun.Status.FAILED;
                return finish(run, status, null, null, ex.getMessage(), toolTrace(plan, null),
                        plan.tokensIn(), plan.tokensOut());
            } catch (RuntimeException ex) {
                log.warn("AI 代理工具执行失败 agentId={} action={}", profile.getUserId(), plan.action(), ex);
                return finish(run, AgentRun.Status.FAILED, null, null, "工具执行失败：" + ex.getMessage(),
                        toolTrace(plan, null), plan.tokensIn(), plan.tokensOut());
            }
            return finish(run, AgentRun.Status.SUCCESS, outcome.outputType(), outcome.outputId(), null,
                    toolTrace(plan, outcome.summary(), outcome.outputId()), plan.tokensIn(), plan.tokensOut());
        } catch (RuntimeException ex) {
            log.warn("AI 代理运行异常 agentId={}", profile.getUserId(), ex);
            return finish(run, AgentRun.Status.FAILED, null, null, "运行异常：" + ex.getMessage(), null,
                    run.getTokensIn(), run.getTokensOut());
        } finally {
            profileService.touchLastRun(profile.getUserId());
        }
    }

    /**
     * 写动作的二次闸门（计划里说要发帖，执行前再查一次频率/去重）。
     */
    private AgentGuard.GuardResult checkAction(AgentProfile profile, AgentPlanner.AgentPlan plan) {
        if ("create_post".equals(plan.action())) {
            String title = argText(plan.args(), "title");
            String content = argText(plan.args(), "content");
            return guard.checkPost(profile, title, content);
        }
        if ("comment".equals(plan.action())) {
            return guard.checkComment(profile);
        }
        return AgentGuard.GuardResult.ok();
    }

    /**
     * 敏感词拦截、越权、业务规则不满足都算「被闸门拦下」，不是系统故障。
     */
    private boolean isGuardRejection(ErrorCode code) {
        return code == ErrorCode.SENSITIVE_WORD_BLOCKED
                || code == ErrorCode.FORBIDDEN
                || code == ErrorCode.BUSINESS_RULE_VIOLATION
                || code == ErrorCode.NOT_JOINED
                || code == ErrorCode.CHANNEL_MUTED
                || code == ErrorCode.VALIDATION_ERROR
                || code == ErrorCode.POST_NOT_FOUND;
    }

    private AgentRun finish(AgentRun run, AgentRun.Status status, String outputType, String outputId,
                            String error, String toolCallsJson, int tokensIn, int tokensOut) {
        run.setStatus(status.name());
        run.setOutputType(outputType == null ? AgentRun.OutputType.NONE.name() : outputType);
        run.setOutputId(outputId);
        run.setError(truncate(error, 500));
        run.setToolCallsJson(toolCallsJson);
        run.setTokensIn(tokensIn);
        run.setTokensOut(tokensOut);
        run.setFinishedAt(ClockTimes.nowUtc());
        runMapper.insert(run);
        return run;
    }

    private String toolTrace(AgentPlanner.AgentPlan plan, String summary) {
        return toolTrace(plan, summary, null);
    }

    private String toolTrace(AgentPlanner.AgentPlan plan, String summary, String outputId) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("action", plan.action());
        trace.put("args", plan.args());
        trace.put("summary", summary);
        trace.put("outputId", outputId);
        return jsonSupport.toJson(trace);
    }

    private String busyHint(AgentProfile profile, boolean noChannels) {
        List<String> hints = new ArrayList<>();
        if (noChannels) {
            hints.add("你还没有加入任何频道，只能 remember/recall 或 none");
        }
        if (!guard.checkPost(profile, null, null).allowed()) {
            hints.add("今天不能再发帖，可以评论或点赞");
        } else if (!guard.checkComment(profile).allowed()) {
            hints.add("今天不能再评论，只能发帖或点赞");
        }
        return String.join("；", hints);
    }

    // ------------------------------------------------------------------
    // 频道
    // ------------------------------------------------------------------

    /**
     * 代理可用频道：人设白名单优先；为空则取已加入频道；仍未加入则自动加入最多 3 个公开频道。
     */
    List<String> resolveChannels(AgentProfile profile) {
        List<String> configured = parseStringList(profile.getChannels());
        if (!configured.isEmpty()) {
            // 人设指定的频道可能还没加入：代理不会「自动获得权限」，必须真的成为成员才能发帖
            ensureMember(configured, profile.getUserId());
            return configured;
        }
        List<String> joined = channelMapper.selectJoinedChannels(profile.getUserId()).stream()
                .map(Channel::getId).toList();
        if (!joined.isEmpty()) {
            return joined;
        }
        List<Channel> candidates = channelMapper.selectRecommendationCandidates(profile.getUserId(),
                AUTO_JOIN_CHANNELS);
        List<String> auto = new ArrayList<>();
        for (Channel channel : candidates) {
            try {
                channelService.joinChannel(channel.getId(), profile.getUserId());
                auto.add(channel.getId());
            } catch (AppException ex) {
                log.debug("代理自动加入频道失败 channelId={} reason={}", channel.getId(), ex.getMessage());
            }
        }
        return auto;
    }

    /**
     * 确保代理是指定频道的成员（公开频道自动加入；私密频道只能由管理员邀请，加入失败只记 debug）。
     */
    private void ensureMember(List<String> channelIds, String agentUserId) {
        for (String channelId : channelIds) {
            if (ChannelAccessService.isActiveMember(accessService.findMember(channelId, agentUserId))) {
                continue;
            }
            try {
                channelService.joinChannel(channelId, agentUserId);
            } catch (AppException ex) {
                log.debug("代理加入指定频道失败 channelId={} reason={}", channelId, ex.getMessage());
            }
        }
    }

    private List<AgentPlanner.ChannelBrief> toBriefs(List<String> channelIds) {
        List<AgentPlanner.ChannelBrief> briefs = new ArrayList<>(channelIds.size());
        for (String channelId : channelIds) {
            Channel channel = channelMapper.selectById(channelId);
            briefs.add(new AgentPlanner.ChannelBrief(channelId, channel == null ? "未知频道" : channel.getName()));
        }
        return briefs;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private List<String> parseStringList(String json) {
        List<String> values = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return values;
        }
        try {
            List<?> raw = jsonSupport.fromJson(json, List.class);
            for (Object item : raw) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    values.add(String.valueOf(item));
                }
            }
        } catch (RuntimeException ex) {
            log.debug("解析频道列表失败：{}", json);
        }
        return values;
    }

    private List<List<Integer>> parseRanges(String json) {
        List<List<Integer>> ranges = new ArrayList<>();
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

    private String argText(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 运行日志分页（后台回放用）。
     */
    public AgentDtos.RunPage listRuns(String agentUserId, Integer page, Integer size) {
        profileService.requireProfile(agentUserId);
        int pageNo = page == null || page <= 0 ? 1 : page;
        int pageSize = size == null || size <= 0 ? 20 : Math.min(size, 100);
        long total = runMapper.countByAgent(agentUserId);
        List<AgentRun> rows = runMapper.selectPage(agentUserId, pageSize, (pageNo - 1) * pageSize);
        return new AgentDtos.RunPage(rows.stream().map(this::toView).toList(), total, pageNo, pageSize);
    }

    public AgentDtos.RunView toView(AgentRun run) {
        return new AgentDtos.RunView(run.getId(), ClockTimes.toInstant(run.getStartedAt()),
                ClockTimes.toInstant(run.getFinishedAt()), run.getOutputType(), run.getOutputId(),
                run.getStatus(), run.getError(),
                run.getTokensIn() == null ? 0 : run.getTokensIn(),
                run.getTokensOut() == null ? 0 : run.getTokensOut(),
                run.getPlanJson(), run.getToolCallsJson());
    }

    /** 一次 tick 的统计。 */
    public record TickSummary(int selected, int ran, int skipped, boolean killSwitchOff) {
    }

    /** 保留给后续「最小间隔」扩展（当前只在 Guard 内部使用）。 */
    static Duration minPostInterval() {
        return AgentGuard.MIN_POST_INTERVAL;
    }
}
