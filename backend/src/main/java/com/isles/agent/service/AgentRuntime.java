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
import com.isles.platform.setting.PlatformSettingService;
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
    /** 注入产出阶段的素材条数上限与单条摘要长度（控制提示词预算）。 */
    private static final int MAX_MATERIALS = 6;
    private static final int MAX_MATERIAL_SNIPPET = 200;
    /** 两阶段 tick 的开关（关掉退回单轮，docs/12 §2.1）。 */
    private static final String SETTING_TWO_PHASE = "agent.twoPhaseEnabled";
    private static final String SETTING_RESEARCH_FIRST = "agent.researchFirst";

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
    private final PlatformSettingService settingService;

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
        if (!AgentProfile.Status.ACTIVE.name().equals(profile.getStatus())) {
            // 已暂停的代理不允许被手动唤醒：只回原因，不写日志行
            return new AgentDtos.RunNowResponse(AgentRun.Status.SKIPPED.name(),
                    "代理已暂停（status=" + profile.getStatus() + "），请先恢复再运行", null);
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
        if (!AgentProfile.Status.ACTIVE.name().equals(profile.getStatus())) {
            // 双保险：调度路径由 shouldWake 挡过，手动路径由 runNow 挡过，这里再挡一次
            log.debug("AI 代理已暂停，跳过 agentId={} status={}", profile.getUserId(), profile.getStatus());
            return null;
        }
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
            AiModelConfig model;
            try {
                model = modelConfigService.resolveDefault();
            } catch (AppException ex) {
                // 零启用模型时 resolveDefault 会抛业务异常：按降级处理为 SKIPPED，不记 FAILED
                log.debug("AI 代理无可用模型，跳过 agentId={} reason={}", profile.getUserId(), ex.getMessage());
                return finish(run, AgentRun.Status.SKIPPED, null, null, "未配置可用的 AI 模型", null, 0, 0);
            }
            if (model == null) {
                // 降级：没有可用模型时只记 SKIPPED，debug 级别，不刷 ERROR
                log.debug("AI 代理无可用模型，跳过 agentId={}", profile.getUserId());
                return finish(run, AgentRun.Status.SKIPPED, null, null, "未配置可用的 AI 模型", null, 0, 0);
            }

            List<String> channelIds = resolveChannels(profile);
            List<AgentPlanner.ChannelBrief> briefs = toBriefs(channelIds);
            List<com.isles.agent.entity.AgentMemory> memories = memoryService.recentTopics(
                    profile.getUserId(), MEMORY_PROMPT_LIMIT);

            AgentToolbox.AgentContext context = new AgentToolbox.AgentContext(profile.getUserId(), profile,
                    channelIds);
            boolean twoPhase = settingService.getBoolean(SETTING_TWO_PHASE);

            // ---------------- 单轮（旧行为）：关掉 twoPhaseEnabled 时使用 ----------------
            if (!twoPhase) {
                AgentPlanner.AgentPlan plan;
                try {
                    plan = planner.plan(model, profile, memories, briefs, busyHint(profile, channelIds.isEmpty()));
                } catch (AppException ex) {
                    return finish(run, AgentRun.Status.FAILED, null, null, ex.getMessage(), null, 0, 0);
                } catch (RuntimeException ex) {
                    log.warn("AI 代理计划阶段失败 agentId={}", profile.getUserId(), ex);
                    return finish(run, AgentRun.Status.FAILED, null, null, "模型调用失败：" + ex.getMessage(),
                            null, 0, 0);
                }
                run.setPlanJson(plan.raw());
                if (!AgentToolbox.ALLOWED_ACTIONS.contains(plan.action())) {
                    return finish(run, AgentRun.Status.GUARDED, null, null,
                            "动作不在白名单：" + plan.action(), null, plan.tokensIn(), plan.tokensOut());
                }
                AgentGuard.GuardResult actionGuard = checkAction(profile, plan);
                if (!actionGuard.allowed()) {
                    return finish(run, AgentRun.Status.GUARDED, null, null, actionGuard.reason(), null,
                            plan.tokensIn(), plan.tokensOut());
                }
                return executePlan(run, plan, context, null, plan.tokensIn(), plan.tokensOut());
            }

            // ---------------- 两阶段：研究 → 产出 ----------------
            boolean researchFirst = settingService.getBoolean(SETTING_RESEARCH_FIRST);
            List<AgentPlanner.AgentPlan> researchPlans = List.of();
            if (researchFirst) {
                try {
                    researchPlans = planner.research(model, profile, memories, briefs);
                } catch (AppException ex) {
                    // 研究阶段失败不阻断 tick：降级为「无素材直接产出」，由闸门兜住
                    log.warn("代理研究阶段失败，按无素材继续 agentId={} reason={}",
                            profile.getUserId(), ex.getMessage());
                } catch (RuntimeException ex) {
                    log.warn("代理研究阶段异常，按无素材继续 agentId={}", profile.getUserId(), ex);
                }
                if (researchPlans.isEmpty()) {
                    // 模型给不出可执行的研究动作 → 用人设兴趣词兜底（不交给模型决定要不要研究）
                    researchPlans = List.of(fallbackResearchPlan(profile, channelIds));
                }
            }

            List<Map<String, Object>> researchSteps = new ArrayList<>();
            List<String> visiblePostIds = new ArrayList<>();
            List<String> materialBlocks = new ArrayList<>();
            int tokensIn = 0;
            int tokensOut = 0;
            for (AgentPlanner.AgentPlan research : researchPlans) {
                tokensIn += research.tokensIn();
                tokensOut += research.tokensOut();
                if (!AgentPlanner.RESEARCH_ACTIONS.contains(research.action())) {
                    researchSteps.add(stepOf(research, null, "研究阶段忽略非只读动作"));
                    continue;
                }
                try {
                    AgentToolbox.ToolOutcome outcome = toolbox.execute(research.action(), research.args(), context);
                    researchSteps.add(stepOf(research, outcome, null));
                    collectPostIds(outcome, visiblePostIds);
                    if (materialBlocks.size() < MAX_MATERIALS) {
                        materialBlocks.add(materialOf(research.action(), outcome));
                    }
                } catch (AppException ex) {
                    log.warn("代理研究动作失败 agentId={} action={} reason={}",
                            profile.getUserId(), research.action(), ex.getMessage());
                    researchSteps.add(stepOf(research, null, ex.getMessage()));
                }
            }

            String materials = String.join("\n", materialBlocks);
            AgentPlanner.AgentPlan compose;
            try {
                compose = planner.compose(model, profile, memories, briefs, materials,
                        busyHint(profile, channelIds.isEmpty()), visiblePostIds);
            } catch (AppException ex) {
                run.setPlanJson(planJsonOf(researchSteps, null));
                return finish(run, AgentRun.Status.FAILED, null, null, ex.getMessage(), null, tokensIn, tokensOut);
            } catch (RuntimeException ex) {
                log.warn("AI 代理产出阶段失败 agentId={}", profile.getUserId(), ex);
                run.setPlanJson(planJsonOf(researchSteps, null));
                return finish(run, AgentRun.Status.FAILED, null, null, "模型调用失败：" + ex.getMessage(),
                        null, tokensIn, tokensOut);
            }
            tokensIn += compose.tokensIn();
            tokensOut += compose.tokensOut();
            run.setPlanJson(planJsonOf(researchSteps, compose));

            if (!AgentToolbox.ALLOWED_ACTIONS.contains(compose.action())) {
                return finish(run, AgentRun.Status.GUARDED, null, null,
                        "动作不在白名单：" + compose.action(), planJsonOf(researchSteps, compose),
                        tokensIn, tokensOut);
            }
            // 禁止编造 id：comment/like 的目标必须来自本 tick 的只读结果
            AgentGuard.GuardResult idGuard = checkTargetId(compose, visiblePostIds);
            if (!idGuard.allowed()) {
                return finish(run, AgentRun.Status.GUARDED, null, null, idGuard.reason(),
                        planJsonOf(researchSteps, compose), tokensIn, tokensOut);
            }
            AgentGuard.GuardResult actionGuard = checkAction(profile, compose);
            if (!actionGuard.allowed()) {
                return finish(run, AgentRun.Status.GUARDED, null, null, actionGuard.reason(),
                        planJsonOf(researchSteps, compose), tokensIn, tokensOut);
            }
            return executePlan(run, compose, context, researchSteps, tokensIn, tokensOut);
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
                || code == ErrorCode.POST_NOT_FOUND
                // P1 联网搜索：配额用尽 / 内网地址被拒 / 抓取失败都属「闸门拦下」，不是系统故障
                || code == ErrorCode.AGENT_SEARCH_QUOTA_EXCEEDED
                || code == ErrorCode.AGENT_FETCH_BLOCKED
                || code == ErrorCode.AGENT_FETCH_FAILED;
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

    /**
     * 执行产出动作并落库（两阶段与单轮共用）。
     *
     * @param researchSteps 研究阶段的轨迹；单轮模式为 null
     */
    private AgentRun executePlan(AgentRun run, AgentPlanner.AgentPlan plan, AgentToolbox.AgentContext context,
                                 List<Map<String, Object>> researchSteps, int tokensIn, int tokensOut) {
        String trace;
        try {
            AgentToolbox.ToolOutcome outcome = toolbox.execute(plan.action(), plan.args(), context);
            trace = traceOf(researchSteps, plan, outcome, null);
            return finish(run, AgentRun.Status.SUCCESS, outcome.outputType(), outcome.outputId(), null,
                    trace, tokensIn, tokensOut);
        } catch (AppException ex) {
            AgentRun.Status status = isGuardRejection(ex.getErrorCode())
                    ? AgentRun.Status.GUARDED
                    : AgentRun.Status.FAILED;
            return finish(run, status, null, null, ex.getMessage(),
                    traceOf(researchSteps, plan, null, ex.getMessage()), tokensIn, tokensOut);
        } catch (RuntimeException ex) {
            log.warn("AI 代理工具执行失败 agentId={} action={}", run.getAgentId(), plan.action(), ex);
            return finish(run, AgentRun.Status.FAILED, null, null, "工具执行失败：" + ex.getMessage(),
                    traceOf(researchSteps, plan, null, ex.getMessage()), tokensIn, tokensOut);
        }
    }

    /**
     * 工具轨迹：单轮保持原有的扁平结构（{@code action/args/summary/outputId}），
     * 两阶段输出 {@code {research:[...], compose:{...}}}。
     *
     * <p>结构化结果（P1-3）直接平铺进对应步骤：{@code web_search} 带 {@code count/results/query}，
     * {@code fetch_page} 带 {@code chars/url}，只读检索带 {@code postIds}——前端不必再从 summary 里抠。
     */
    private String traceOf(List<Map<String, Object>> researchSteps, AgentPlanner.AgentPlan plan,
                           AgentToolbox.ToolOutcome outcome, String error) {
        Map<String, Object> composeStep = stepOf(plan, outcome, error);
        if (researchSteps == null) {
            return jsonSupport.toJson(composeStep);
        }
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("research", researchSteps);
        trace.put("compose", composeStep);
        return jsonSupport.toJson(trace);
    }

    /**
     * 单个步骤的轨迹：动作 + 入参 + 摘要 + 产出 id + 工具的结构化数据。
     */
    private Map<String, Object> stepOf(AgentPlanner.AgentPlan plan, AgentToolbox.ToolOutcome outcome,
                                       String error) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("action", plan.action());
        step.put("args", plan.args());
        step.put("summary", outcome == null ? null : truncate(outcome.summary(), 2000));
        step.put("outputId", outcome == null ? null : outcome.outputId());
        if (outcome != null && !outcome.data().isEmpty()) {
            // 平铺结构化字段：count / results / query / chars / url / postIds / fallback
            step.putAll(outcome.data());
        }
        if (error != null) {
            step.put("error", truncate(error, 300));
        }
        return step;
    }

    /**
     * 研究阶段产出 → 注入产出阶段的「素材」文本（标题 + 摘要 + url）。
     */
    private String materialOf(String action, AgentToolbox.ToolOutcome outcome) {
        StringBuilder block = new StringBuilder();
        Object results = outcome.data().get("results");
        if (results instanceof List<?> list && !list.isEmpty()) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                block.append("- [").append(map.get("engine")).append("] ").append(map.get("title"))
                        .append('\n').append("  ").append(map.get("url")).append('\n');
                Object snippet = map.get("snippet");
                if (snippet != null) {
                    block.append("  ").append(truncate(String.valueOf(snippet), MAX_MATERIAL_SNIPPET)).append('\n');
                }
            }
        }
        if (block.isEmpty()) {
            // 站内检索 / 抓正文 / 兜底：直接用摘要（已含 id、标题、正文片段）
            block.append("[").append(action).append("]\n")
                    .append(truncate(outcome.summary(), MAX_MATERIAL_SNIPPET * 3));
        }
        return block.toString();
    }

    /**
     * 收集「本 tick 可见的帖子 id」：只有只读检索类工具才有意义（写动作的目标白名单）。
     */
    private void collectPostIds(AgentToolbox.ToolOutcome outcome, List<String> sink) {
        Object ids = outcome.data().get("postIds");
        if (ids instanceof List<?> list) {
            for (Object id : list) {
                if (id != null && !sink.contains(String.valueOf(id))) {
                    sink.add(String.valueOf(id));
                }
            }
        }
    }

    /**
     * 研究阶段兜底：模型没给出可执行动作时，用人设兴趣词强制发起一次联网搜索
     * （没有兴趣/频道就退化为逛频道，至少让产出阶段有站内素材）。
     */
    private AgentPlanner.AgentPlan fallbackResearchPlan(AgentProfile profile, List<String> channelIds) {
        List<String> interests = parseStringList(profile.getInterests());
        String query = interests.isEmpty() ? null : String.join(" ", interests);
        if (query == null && profile.getBio() != null && !profile.getBio().isBlank()) {
            query = profile.getBio().trim();
        }
        if (query != null) {
            return new AgentPlanner.AgentPlan("web_search", Map.of("query", query), "{}", 0, 0);
        }
        Map<String, Object> args = new LinkedHashMap<>();
        if (!channelIds.isEmpty()) {
            args.put("channelId", channelIds.get(0));
        }
        return new AgentPlanner.AgentPlan("list_channel_posts", args, "{}", 0, 0);
    }

    /**
     * 两阶段计划原文：{@code {"research":[...原文...],"compose":{...原文...}}}，便于后台回放。
     */
    private String planJsonOf(List<Map<String, Object>> researchSteps, AgentPlanner.AgentPlan compose) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("research", researchSteps);
        if (compose == null) {
            plan.put("compose", null);
        } else {
            // 与成功路径的 traceOf 保持同构：对象而非模型原文，否则前端两种形态都要猜
            Map<String, Object> composeNode = new LinkedHashMap<>();
            composeNode.put("action", compose.action());
            composeNode.put("args", compose.args());
            plan.put("compose", composeNode);
        }
        return jsonSupport.toJson(plan);
    }

    /**
     * 禁止编造 id：comment/like 的目标必须出现在本 tick 的只读结果里。
     *
     * <p>模型曾经把 postId 拼成「频道ID_2024-06-15_14:30:00」这种凭空构造的值，
     * 所以这里做硬校验，而不是靠提示词。
     */
    private AgentGuard.GuardResult checkTargetId(AgentPlanner.AgentPlan plan, List<String> visiblePostIds) {
        if ("comment".equals(plan.action())) {
            String postId = argText(plan.args(), "postId");
            return requireVisible(postId, visiblePostIds, "postId");
        }
        if ("like".equals(plan.action())) {
            String targetType = argText(plan.args(), "targetType");
            String targetId = argText(plan.args(), "targetId");
            if (targetType != null && "COMMENT".equalsIgnoreCase(targetType)) {
                // 评论 id 不在本 tick 可见列表内（只读工具不返回评论 id），一律拒绝，避免编造
                return AgentGuard.GuardResult.blocked("点赞评论需要真实评论 id，本轮不可见，已拒绝");
            }
            return requireVisible(targetId, visiblePostIds, "targetId");
        }
        return AgentGuard.GuardResult.ok();
    }

    private AgentGuard.GuardResult requireVisible(String id, List<String> visiblePostIds, String field) {
        if (id == null || id.isBlank()) {
            return AgentGuard.GuardResult.blocked(field + " 为空，已拒绝");
        }
        if (!visiblePostIds.contains(id)) {
            return AgentGuard.GuardResult.blocked(field + " 不在本轮可见列表内：" + truncate(id, 64));
        }
        return AgentGuard.GuardResult.ok();
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
