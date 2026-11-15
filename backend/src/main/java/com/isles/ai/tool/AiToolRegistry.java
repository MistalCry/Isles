package com.isles.ai.tool;

import com.isles.ai.dto.AiContextPost;
import com.isles.ai.mapper.AiContextMapper;
import com.isles.ai.provider.AiToolDefinition;
import com.isles.ai.service.AiContextService;
import com.isles.common.json.JsonSupport;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 工具注册表（docs/05 §2.13、docs/11 §5.3）：定义工具 schema 并执行模型发起的调用。
 *
 * <p>工具只读，且沿用检索的可见性规则（公开可见频道的已发布帖子；频道助手可读本频道），
 * 不会因为模型编造 id 而泄露私密内容。
 *
 * <p>参数解析失败、工具不存在、帖子不可见都返回结构化错误给模型，由模型决定如何向用户解释，
 * 不抛异常打断对话。
 */
@Component
@RequiredArgsConstructor
public class AiToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiToolRegistry.class);

    public static final String SEARCH_CHANNEL_POSTS = "search_channel_posts";
    public static final String SEARCH_GLOBAL_POSTS = "search_global_posts";
    public static final String GET_POST_DETAIL = "get_post_detail";

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 8;
    private static final int DETAIL_EXCERPT = 800;

    private final AiContextService contextService;
    private final AiContextMapper contextMapper;
    private final JsonSupport jsonSupport;

    /**
     * 工具定义：频道会话才提供「本频道检索」，全站检索与帖子详情对所有会话开放。
     */
    public List<AiToolDefinition> definitions(boolean channelScope) {
        List<AiToolDefinition> tools = new ArrayList<>();
        if (channelScope) {
            tools.add(new AiToolDefinition(
                    SEARCH_CHANNEL_POSTS,
                    "在当前频道内按关键词检索已发布的帖子。回答涉及本频道讨论过的话题时必须先调用它。",
                    querySchema("检索关键词，尽量使用帖子中可能出现的词")));
        }
        tools.add(new AiToolDefinition(
                SEARCH_GLOBAL_POSTS,
                "在全站公开频道内按关键词检索已发布的帖子。需要了解社区整体讨论时调用。",
                querySchema("检索关键词，尽量使用帖子中可能出现的词")));
        tools.add(new AiToolDefinition(
                GET_POST_DETAIL,
                "按帖子 id 读取帖子正文详情。通常先用检索工具拿到 postId，再用它读取完整内容。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "postId", Map.of("type", "string", "description", "帖子 id（检索结果中的 postId）")),
                        "required", List.of("postId"))));
        return List.copyOf(tools);
    }

    /**
     * 执行一次工具调用。
     *
     * @param channelId 频道会话的频道 id（全站会话为 null）
     */
    public ToolOutcome execute(String toolName, String argumentsJson, String channelId) {
        String name = toolName == null ? "" : toolName.trim();
        Map<String, Object> arguments = parseArguments(argumentsJson);
        try {
            return switch (name) {
                case SEARCH_CHANNEL_POSTS -> searchChannel(arguments, channelId);
                case SEARCH_GLOBAL_POSTS -> searchGlobal(arguments);
                case GET_POST_DETAIL -> postDetail(arguments, channelId);
                default -> error("unknown_tool", "不支持的工具：" + name);
            };
        } catch (RuntimeException ex) {
            log.warn("工具执行失败 tool={} message={}", name, ex.getMessage());
            return error("tool_failed", "工具执行失败，请换一种方式检索或直接回答");
        }
    }

    // ---------------------------------------------------------------------
    // 具体工具
    // ---------------------------------------------------------------------

    private ToolOutcome searchChannel(Map<String, Object> arguments, String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return error("not_in_channel", "当前会话不在频道内，无法检索频道帖子");
        }
        List<AiContextPost> posts = contextService.retrieve(query(arguments), channelId, limit(arguments));
        return hits(posts, sourcesPayload(posts, "本频道"));
    }

    private ToolOutcome searchGlobal(Map<String, Object> arguments) {
        List<AiContextPost> posts = contextService.retrieve(query(arguments), null, limit(arguments));
        return hits(posts, sourcesPayload(posts, "全站"));
    }

    private ToolOutcome postDetail(Map<String, Object> arguments, String channelId) {
        Object raw = arguments.get("postId");
        String postId = raw == null ? null : raw.toString().trim();
        if (postId == null || postId.isBlank()) {
            return error("invalid_argument", "缺少 postId 参数");
        }
        AiContextPost post = contextMapper.selectContextPostById(postId, channelId);
        if (post == null) {
            return error("post_not_found", "帖子不存在或不可见");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("postId", post.getId());
        payload.put("title", title(post));
        payload.put("channelName", post.getChannelName());
        payload.put("content", excerpt(post.getContent(), DETAIL_EXCERPT));
        payload.put("likeCount", post.getLikeCount());
        payload.put("commentCount", post.getCommentCount());
        payload.put("url", "/posts/" + post.getId());
        return hits(List.of(post), payload);
    }

    // ---------------------------------------------------------------------
    // 工具结果序列化
    // ---------------------------------------------------------------------

    private ToolOutcome hits(List<AiContextPost> posts, Object payload) {
        return new ToolOutcome(jsonSupport.toJson(payload), posts == null ? List.of() : List.copyOf(posts));
    }

    private ToolOutcome error(String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", code);
        payload.put("message", message);
        return new ToolOutcome(jsonSupport.toJson(payload), List.of());
    }

    private Map<String, Object> sourcesPayload(List<AiContextPost> posts, String scopeLabel) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (posts != null) {
            for (AiContextPost post : posts) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("postId", post.getId());
                item.put("title", title(post));
                item.put("channelName", post.getChannelName());
                item.put("excerpt", excerpt(post.getContent(), 200));
                item.put("likeCount", post.getLikeCount());
                item.put("url", "/posts/" + post.getId());
                items.add(item);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", scopeLabel);
        payload.put("total", items.size());
        payload.put("results", items);
        if (items.isEmpty()) {
            payload.put("hint", "没有命中内容，可换更短/更通用的关键词，或如实说明社区中未找到");
        }
        return payload;
    }

    private Map<String, Object> querySchema(String queryDescription) {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", queryDescription),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "返回条数，1-" + MAX_LIMIT + "，默认 " + DEFAULT_LIMIT,
                                "minimum", 1,
                                "maximum", MAX_LIMIT)),
                "required", List.of("query"));
    }

    private String query(Map<String, Object> arguments) {
        Object raw = arguments.get("query");
        return raw == null ? "" : raw.toString();
    }

    private int limit(Map<String, Object> arguments) {
        Object raw = arguments.get("limit");
        if (raw instanceof Number number) {
            return Math.max(1, Math.min(MAX_LIMIT, number.intValue()));
        }
        if (raw != null) {
            try {
                return Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(raw.toString().trim())));
            } catch (NumberFormatException ignored) {
                // 模型偶尔把数字写成字符串或乱填，回落默认值
            }
        }
        return DEFAULT_LIMIT;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = jsonSupport.fromJson(argumentsJson, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException ex) {
            log.warn("工具参数不是合法 JSON，按空参数处理 arguments={}", argumentsJson);
            return Map.of();
        }
    }

    private String title(AiContextPost post) {
        if (post.getTitle() != null && !post.getTitle().isBlank()) {
            return post.getTitle();
        }
        return excerpt(post.getContent(), 40);
    }

    private String excerpt(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String text = content.replaceAll("<[^>]*>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }

    /**
     * 工具执行结果。
     *
     * @param content 回填给模型的 JSON 字符串
     * @param posts   本次真正命中的帖子（用于生成来源引用）
     */
    public record ToolOutcome(String content, List<AiContextPost> posts) {

        /** 合并来源命中（按帖子 id 去重，保持首次出现顺序）。 */
        public static List<AiContextPost> merge(List<AiContextPost> target, List<AiContextPost> posts) {
            if (posts == null || posts.isEmpty()) {
                return target;
            }
            Set<String> existing = new LinkedHashSet<>();
            for (AiContextPost post : target) {
                existing.add(post.getId());
            }
            List<AiContextPost> merged = new ArrayList<>(target);
            for (AiContextPost post : posts) {
                if (post != null && existing.add(post.getId())) {
                    merged.add(post);
                }
            }
            return merged;
        }
    }
}
