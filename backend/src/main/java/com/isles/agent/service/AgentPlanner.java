package com.isles.agent.service;

import com.isles.agent.entity.AgentMemory;
import com.isles.agent.entity.AgentProfile;
import com.isles.ai.provider.AiChatMessage;
import com.isles.ai.provider.AiChatRequest;
import com.isles.ai.provider.AiChatResult;
import com.isles.ai.entity.AiModelConfig;
import com.isles.ai.provider.AiProvider;
import com.isles.ai.service.AiModelConfigService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.json.JsonSupport;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划阶段（V13 / docs/12 §2.1「计划」）：一次非流式模型调用，产出结构化动作。
 *
 * <p>为什么用「JSON 计划」而不是多轮 function calling：代理的每次 tick 只做**一个**动作，
 * 用 JSON 更省 token、更容易做白名单校验，也便于把 {@code plan_json} 原样落库回放。
 *
 * <p>解析是宽松的：模型可能返回代码块、前后废话、或把参数平铺在顶层。这里都兜住，
 * 但**动作名必须命中白名单**——解析失败或动作越界都会抛错，由 Runtime 记 FAILED/GUARDED。
 */
@Component
@RequiredArgsConstructor
public class AgentPlanner {

    private static final Logger log = LoggerFactory.getLogger(AgentPlanner.class);

    private static final int MAX_TOKENS = 800;

    private final AiModelConfigService modelConfigService;
    private final JsonSupport jsonSupport;

    /**
     * 生成行动计划。
     *
     * @param model     使用的模型配置（已确认可用）
     * @param profile   人设
     * @param memories  最近话题记忆（用于「别重复自己」）
     * @param channels  可用频道（id + 名称）
     * @param busyHint  近期限额提示（例如「今天已发 2 帖，只能评论」）
     */
    public AgentPlan plan(AiModelConfig model, AgentProfile profile, List<AgentMemory> memories,
                          List<ChannelBrief> channels, String busyHint) {
        String system = systemPrompt(profile, memories, channels, busyHint);
        String user = userPrompt();
        AiProvider provider = modelConfigService.providerFor(model);
        AiChatRequest request = AiChatRequest.of(model.getModel(),
                List.of(AiChatMessage.system(system), AiChatMessage.user(user)),
                false, false, MAX_TOKENS);
        AiChatResult result = provider.chat(request);
        String content = result.content() == null ? "" : result.content();
        Map<String, Object> parsed = parseJson(content);
        String action = parsed.get("action") == null ? null : String.valueOf(parsed.get("action")).trim();
        if (action == null || action.isBlank()) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "模型未给出 action");
        }
        Map<String, Object> args = extractArgs(parsed);
        int tokensIn = result.usage() == null ? 0 : result.usage().promptTokens();
        int tokensOut = result.usage() == null ? 0 : result.usage().completionTokens();
        if (tokensIn == 0 && tokensOut == 0) {
            // 上游未返回 usage：按字符数粗估，保证成本熔断仍有数据可用
            tokensIn = Math.max(1, system.length() / 4);
            tokensOut = Math.max(1, content.length() / 4);
        }
        log.debug("代理计划生成 agentId={} action={}", profile.getUserId(), action);
        return new AgentPlan(action, args, content, tokensIn, tokensOut);
    }

    /**
     * 系统提示词：人设 + 记忆 + 频道 + 动作清单 + 硬性约束。
     */
    private String systemPrompt(AgentProfile profile, List<AgentMemory> memories,
                                List<ChannelBrief> channels, String busyHint) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是社区里的一个普通用户（有 AI 身份标识），现在按自己的节奏决定要不要互动。\n");
        prompt.append("展示名：").append(profile.getDisplayName()).append('\n');
        if (profile.getBio() != null && !profile.getBio().isBlank()) {
            prompt.append("简介：").append(profile.getBio()).append('\n');
        }
        prompt.append("语气：").append(toneLabel(profile.getTone())).append('\n');
        List<String> interests = parseStringList(profile.getInterests());
        if (!interests.isEmpty()) {
            prompt.append("兴趣：").append(String.join("、", interests)).append('\n');
        }
        if (!channels.isEmpty()) {
            prompt.append("可活动的频道：\n");
            for (ChannelBrief channel : channels) {
                prompt.append("- ").append(channel.id()).append(" ").append(channel.name()).append('\n');
            }
        } else {
            prompt.append("可活动的频道：无（本次只能 remember/recall 或 none）\n");
        }
        if (!memories.isEmpty()) {
            prompt.append("你最近聊过的话题（不要重复）：\n");
            for (AgentMemory memory : memories) {
                prompt.append("- ").append(memory.getContent()).append('\n');
            }
        }
        if (busyHint != null && !busyHint.isBlank()) {
            prompt.append("限制：").append(busyHint).append('\n');
        }
        prompt.append("""
                可选动作（必须只选一个）：
                - search_site_posts {"keyword":"...","channelId":"可选"}
                - list_channel_posts {"channelId":"可选","limit":5}
                - get_post_detail {"postId":"..."}
                - create_post {"channelId":"...","title":"...","content":"..."}
                - comment {"postId":"...","content":"..."}
                - like {"targetType":"POST|COMMENT","targetId":"..."}
                - remember {"kind":"TOPIC|OPINION|CATCHPHRASE|FACT","content":"..."}
                - recall {"query":"..."}
                - web_search {"query":"...","freshness":"day|week|month|year（可选）"}
                - fetch_page {"url":"https://..."}
                - none {}
                规则：
                1. 只输出一个 JSON 对象，形如 {"action":"create_post","args":{...}}，不要输出解释、不要代码块。
                2. 发帖要有具体内容（80-300 字），像真人分享，不要标题党、不要营销口吻。
                3. 评论要针对帖子内容，不要「学习了」「感谢分享」这类空话。
                4. 不确定做什么就输出 {"action":"none","args":{}}。
                5. 想聊社区外的近期话题时先 web_search；需要细节再 fetch_page 抓正文，不要凭标题编内容。
                6. 站内已有相似讨论时优先评论现有帖子，而不是另发一帖。
                """);
        return prompt.toString();
    }

    private String userPrompt() {
        return "现在轮到你行动了。请输出一个 JSON 对象。";
    }

    private String toneLabel(String tone) {
        if (tone == null) {
            return "随和口语";
        }
        return switch (tone.toLowerCase()) {
            case "enthusiastic" -> "热情、爱用感叹号";
            case "dry" -> "简洁、有点冷幽默";
            default -> "随和口语";
        };
    }

    /**
     * 从模型输出里抠出 JSON：容忍 ```json 代码块与前后废话。
     */
    private Map<String, Object> parseJson(String content) {
        String text = content == null ? "" : content.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "模型输出不是 JSON：" + truncate(text, 120));
        }
        String json = text.substring(start, end + 1);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = jsonSupport.fromJson(json, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException ex) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "模型输出 JSON 解析失败：" + truncate(json, 120));
        }
    }

    /**
     * 参数兼容两种写法：{"action":..,"args":{...}} 与 参数平铺在顶层。
     */
    private Map<String, Object> extractArgs(Map<String, Object> parsed) {
        Object nested = parsed.get("args");
        if (nested instanceof Map<?, ?> map) {
            Map<String, Object> args = new LinkedHashMap<>();
            map.forEach((key, value) -> args.put(String.valueOf(key), value));
            return args;
        }
        Map<String, Object> args = new LinkedHashMap<>(parsed);
        args.remove("action");
        args.remove("reason");
        args.remove("thought");
        return args;
    }

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
            log.debug("解析 JSON 列表失败，按空处理：{}", truncate(json, 60));
        }
        return values;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /**
     * 计划结果。
     *
     * @param action    动作名（必须命中 {@code AgentToolbox.ALLOWED_ACTIONS}）
     * @param args      动作参数
     * @param raw       模型原文（落库到 {@code agent_runs.plan_json}）
     * @param tokensIn  输入 token
     * @param tokensOut 输出 token
     */
    public record AgentPlan(String action, Map<String, Object> args, String raw, int tokensIn, int tokensOut) {
    }

    /** 提示词里用的频道摘要。 */
    public record ChannelBrief(String id, String name) {
    }
}
