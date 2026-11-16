package com.isles.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.isles.agent.entity.AgentMemory;
import com.isles.agent.search.PageFetcher;
import com.isles.agent.search.SearchProvider;
import com.isles.agent.search.SearchQuota;
import com.isles.agent.search.SearchResult;
import com.isles.agent.entity.AgentProfile;
import com.isles.agent.service.AgentMemoryService;
import com.isles.ai.dto.AiContextPost;
import com.isles.ai.mapper.AiContextMapper;
import com.isles.channel.entity.Board;
import com.isles.channel.entity.Channel;
import com.isles.channel.mapper.BoardMapper;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.comment.dto.CreateCommentRequest;
import com.isles.comment.service.CommentService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.interaction.service.InteractionService;
import com.isles.post.dto.CreatePostRequest;
import com.isles.post.dto.PostResponse;
import com.isles.post.service.PostService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 代理工具集（V13 / docs/12 §4，P0 范围）。
 *
 * <p>P0 八个工具：{@code search_site_posts}、{@code list_channel_posts}、{@code get_post_detail}、
 * {@code create_post}、{@code comment}、{@code like}、{@code remember}、{@code recall}；
 * P1 追加 {@code web_search}（联网搜索）与 {@code fetch_page}（抓正文）。
 *
 * <p>两条硬约束：
 * <ol>
 *   <li><b>动作白名单</b>：不在 {@link #ALLOWED_ACTIONS} 里的动作直接拒绝（{@link ErrorCode#FORBIDDEN}），
 *       不做「尽力执行」；</li>
 *   <li><b>复用现有 Service</b>：发帖走 {@link PostService}、评论走 {@link CommentService}、点赞走
 *       {@link InteractionService}，因此敏感词三模式、禁言、频率与权限校验全部照旧生效，工具层不绕过任何规则。</li>
 * </ol>
 *
 * <p>工具**不得**触达私信、举报、处罚、平台配置（docs/12 §4「权限边界」）——这些能力根本没有对应工具。
 */
@Component
@RequiredArgsConstructor
public class AgentToolbox {

    private static final Logger log = LoggerFactory.getLogger(AgentToolbox.class);

    /** P0 允许的动作（docs/12 §4）。 */
    public static final Set<String> ALLOWED_ACTIONS = Set.of(
            "search_site_posts", "list_channel_posts", "get_post_detail",
            "create_post", "comment", "like", "remember", "recall",
            "web_search", "fetch_page", "none");

    private static final int MAX_SEARCH_LIMIT = 8;
    /** 单次给模型的联网结果上限（与 SearchProvider 内部封顶一致）。 */
    private static final int MAX_WEB_RESULTS = 10;
    /** 每条联网结果在提示词里的摘要长度。 */
    private static final int MAX_WEB_SNIPPET = 120;
    private static final int MAX_SUMMARY_POSTS = 5;

    private final AiContextMapper contextMapper;
    private final PostService postService;
    private final CommentService commentService;
    private final InteractionService interactionService;
    private final BoardMapper boardMapper;
    private final ChannelMapper channelMapper;
    private final AgentMemoryService memoryService;
    private final SearchProvider searchProvider;
    private final SearchQuota searchQuota;
    private final PageFetcher pageFetcher;

    /**
     * 执行一个动作。
     *
     * @param action 动作名（必须命中白名单）
     * @param args   模型给出的参数（可能缺字段、类型不对，全部按「宽松解析 + 明确报错」处理）
     * @param context 代理上下文（谁、人设、允许频道）
     */
    public ToolOutcome execute(String action, Map<String, Object> args, AgentContext context) {
        if (action == null || action.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "动作不能为空");
        }
        String name = action.trim();
        if (!ALLOWED_ACTIONS.contains(name)) {
            throw new AppException(ErrorCode.FORBIDDEN, "不允许的动作：" + name);
        }
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        return switch (name) {
            case "none" -> new ToolOutcome(AgentRunTypes.NONE, null, "本次不行动");
            case "search_site_posts" -> searchSitePosts(safeArgs, context);
            case "list_channel_posts" -> listChannelPosts(safeArgs, context);
            case "get_post_detail" -> getPostDetail(safeArgs, context);
            case "create_post" -> createPost(safeArgs, context);
            case "comment" -> comment(safeArgs, context);
            case "like" -> like(safeArgs, context);
            case "remember" -> remember(safeArgs, context);
            case "recall" -> recall(safeArgs, context);
            case "web_search" -> webSearch(safeArgs, context);
            case "fetch_page" -> fetchPage(safeArgs);
            default -> throw new AppException(ErrorCode.FORBIDDEN, "不允许的动作：" + name);
        };
    }

    // ------------------------------------------------------------------
    // 只读工具
    // ------------------------------------------------------------------

    private ToolOutcome searchSitePosts(Map<String, Object> args, AgentContext context) {
        String keyword = str(args, "keyword", str(args, "query", null));
        if (keyword == null || keyword.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "search_site_posts 需要 keyword");
        }
        String channelId = resolveChannelId(str(args, "channelId", null), context);
        List<AiContextPost> posts = contextMapper.searchContextPosts(patterns(keyword), channelId,
                clampInt(args, "limit", 5, 1, MAX_SEARCH_LIMIT));
        return new ToolOutcome(AgentRunTypes.NONE, null, summarizePosts("站内检索结果", posts));
    }

    /**
     * 联网搜索（P1）。三种结局：
     * <ol>
     *   <li>配额用尽 → 抛 {@link ErrorCode#AGENT_SEARCH_QUOTA_EXCEEDED}（**不静默失败**，让模型/后台看得见）；</li>
     *   <li>provider=NONE 或搜索失败/空结果 → 退回站内检索，WARN 一行，不阻塞 tick；</li>
     *   <li>有结果 → 返回前 N 条摘要，并把命中的 url 写进记忆（{@code kind=SOURCE}）供追溯与去重。</li>
     * </ol>
     */
    private ToolOutcome webSearch(Map<String, Object> args, AgentContext context) {
        String query = str(args, "query", null);
        if (query == null || query.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "web_search 需要 query");
        }
        String freshness = str(args, "freshness", null);
        int limit = clampInt(args, "limit", MAX_WEB_RESULTS, 1, MAX_WEB_RESULTS);
        if (!searchQuota.tryConsume()) {
            throw new AppException(ErrorCode.AGENT_SEARCH_QUOTA_EXCEEDED,
                    "今日联网搜索配额已用尽（" + searchQuota.used() + "/" + searchQuota.limit() + "）");
        }
        if (!searchProvider.available()) {
            log.warn("联网搜索未启用（provider={}），退回站内检索 query={}", searchProvider.providerType(), query);
            return fallbackToSiteSearch(query, context, "联网搜索未启用，已退回站内检索");
        }
        List<SearchResult> results = searchProvider.search(query, freshness, limit);
        if (results.isEmpty()) {
            log.warn("联网搜索无结果（或引擎全部失败），退回站内检索 query={}", query);
            return fallbackToSiteSearch(query, context, "联网搜索无结果，已退回站内检索");
        }
        // 记忆：命中的 url 写入 SOURCE，供后续选题去重与内容追溯（docs/12 §5.3）
        for (SearchResult result : results) {
            memoryService.remember(context.agentUserId(), AgentMemory.Kind.SOURCE.name(),
                    truncate(result.title() == null ? result.url() : result.title(), 200) + " " + result.url(),
                    result.url());
        }
        return new ToolOutcome(AgentRunTypes.NONE, null, summarizeSearchResults(query, results));
    }

    /**
     * 抓取网页正文（P1）。SSRF 校验在 {@link PageFetcher} 内完成。
     */
    private ToolOutcome fetchPage(Map<String, Object> args) {
        String url = str(args, "url", null);
        if (url == null || url.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "fetch_page 需要 url");
        }
        String text = pageFetcher.fetch(url);
        return new ToolOutcome(AgentRunTypes.NONE, null, "页面正文（" + url + "）：\n" + text);
    }

    private ToolOutcome fallbackToSiteSearch(String keyword, AgentContext context, String reason) {
        String channelId = resolveChannelId(null, context);
        List<AiContextPost> posts = contextMapper.searchContextPosts(patterns(keyword), channelId,
                MAX_SEARCH_LIMIT);
        return new ToolOutcome(AgentRunTypes.NONE, null,
                reason + "\n" + summarizePosts("站内检索结果", posts));
    }

    private String summarizeSearchResults(String query, List<SearchResult> results) {
        StringBuilder builder = new StringBuilder("联网搜索「").append(query).append("」（")
                .append(results.size()).append(" 条）：");
        for (SearchResult result : results) {
            builder.append("\n- [").append(nullSafe(result.engine())).append("] ")
                    .append(nullSafe(result.title()))
                    .append(" | ").append(result.url());
            if (result.content() != null && !result.content().isBlank()) {
                builder.append("\n  ").append(truncate(result.content(), MAX_WEB_SNIPPET));
            }
            if (result.publishedDate() != null) {
                builder.append("\n  发布：").append(result.publishedDate());
            }
        }
        return builder.toString();
    }

    private ToolOutcome listChannelPosts(Map<String, Object> args, AgentContext context) {
        String channelId = resolveChannelId(str(args, "channelId", null), context);
        if (channelId == null) {
            return new ToolOutcome(AgentRunTypes.NONE, null, "代理尚未加入任何频道，无法浏览");
        }
        List<AiContextPost> posts = contextMapper.selectRecentPosts(channelId, null,
                clampInt(args, "limit", 5, 1, MAX_SEARCH_LIMIT));
        return new ToolOutcome(AgentRunTypes.NONE, null, summarizePosts("频道最新帖", posts));
    }

    private ToolOutcome getPostDetail(Map<String, Object> args, AgentContext context) {
        String postId = str(args, "postId", null);
        if (postId == null || postId.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "get_post_detail 需要 postId");
        }
        AiContextPost post = contextMapper.selectContextPostById(postId, null);
        if (post == null) {
            throw new AppException(ErrorCode.POST_NOT_FOUND);
        }
        String content = post.getContent() == null ? "" : post.getContent().replaceAll("<[^>]*>", " ");
        return new ToolOutcome(AgentRunTypes.NONE, null,
                "帖子《" + nullSafe(post.getTitle()) + "》id=" + post.getId()
                        + " 频道=" + nullSafe(post.getChannelName())
                        + " 正文：" + truncate(content.trim(), 800));
    }

    // ------------------------------------------------------------------
    // 写工具（全部复用现有 Service）
    // ------------------------------------------------------------------

    private ToolOutcome createPost(Map<String, Object> args, AgentContext context) {
        String channelId = resolveChannelId(str(args, "channelId", null), context);
        if (channelId == null) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "create_post 需要 channelId（代理未加入任何频道）");
        }
        String content = str(args, "content", null);
        if (content == null || content.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "create_post 需要 content");
        }
        String boardId = str(args, "boardId", null);
        if (boardId == null || boardId.isBlank()) {
            boardId = defaultBoardId(channelId);
        }
        if (boardId == null) {
            throw new AppException(ErrorCode.BOARD_NOT_FOUND, "频道没有可用板块");
        }
        CreatePostRequest request = new CreatePostRequest(boardId, str(args, "title", null), content,
                "PLAIN", null, List.of());
        PostResponse post = postService.createPost(channelId, request, context.agentUserId());
        // 话题指纹：去重的唯一依据（docs/12 §5.3）
        memoryService.remember(context.agentUserId(), AgentMemory.Kind.TOPIC.name(),
                titleOf(post), post.id());
        return new ToolOutcome(AgentRunTypes.POST, post.id(), "已发帖《" + nullSafe(post.title()) + "》");
    }

    private ToolOutcome comment(Map<String, Object> args, AgentContext context) {
        String postId = str(args, "postId", null);
        String content = str(args, "content", null);
        if (postId == null || postId.isBlank() || content == null || content.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "comment 需要 postId 与 content");
        }
        CreateCommentRequest request = new CreateCommentRequest(content, str(args, "parentId", null),
                str(args, "replyTo", null), List.of(), List.of());
        var comment = commentService.createComment(postId, request, context.agentUserId());
        return new ToolOutcome(AgentRunTypes.COMMENT, comment.id(), "已评论帖子 " + postId);
    }

    private ToolOutcome like(Map<String, Object> args, AgentContext context) {
        String targetType = str(args, "targetType", "POST");
        String targetId = str(args, "targetId", null);
        if (targetId == null || targetId.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "like 需要 targetId");
        }
        if ("COMMENT".equalsIgnoreCase(targetType)) {
            interactionService.likeComment(targetId, context.agentUserId());
            return new ToolOutcome(AgentRunTypes.LIKE, targetId, "已点赞评论 " + targetId);
        }
        interactionService.likePost(targetId, context.agentUserId());
        return new ToolOutcome(AgentRunTypes.LIKE, targetId, "已点赞帖子 " + targetId);
    }

    // ------------------------------------------------------------------
    // 记忆工具
    // ------------------------------------------------------------------

    private ToolOutcome remember(Map<String, Object> args, AgentContext context) {
        String content = str(args, "content", null);
        if (content == null || content.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "remember 需要 content");
        }
        String kind = str(args, "kind", AgentMemory.Kind.FACT.name());
        String id = memoryService.remember(context.agentUserId(), kind, content, str(args, "sourceRef", null));
        return new ToolOutcome(AgentRunTypes.NONE, id,
                id == null ? "记忆已存在，跳过" : "已记住：" + truncate(content, 60));
    }

    private ToolOutcome recall(Map<String, Object> args, AgentContext context) {
        String query = str(args, "query", null);
        List<AgentMemory> memories = memoryService.recall(context.agentUserId(), query,
                clampInt(args, "limit", 5, 1, 10));
        if (memories.isEmpty()) {
            return new ToolOutcome(AgentRunTypes.NONE, null, "没有相关记忆");
        }
        StringBuilder builder = new StringBuilder("相关记忆：");
        for (AgentMemory memory : memories) {
            builder.append("\n- [").append(memory.getKind()).append("] ").append(memory.getContent());
        }
        return new ToolOutcome(AgentRunTypes.NONE, null, builder.toString());
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 频道解析：显式指定且在白名单内才用；否则取代理第一个可用频道。
     *
     * <p>白名单来自人设的 {@code channels}，为空时由 Runtime 预先算好「代理已加入的频道」。
     */
    private String resolveChannelId(String requested, AgentContext context) {
        if (requested != null && !requested.isBlank()) {
            if (context.channelIds().isEmpty() || context.channelIds().contains(requested)) {
                return requested;
            }
            throw new AppException(ErrorCode.FORBIDDEN, "代理未获准在该频道活动：" + requested);
        }
        return context.channelIds().isEmpty() ? null : context.channelIds().get(0);
    }

    private String defaultBoardId(String channelId) {
        List<Board> boards = boardMapper.selectList(new LambdaQueryWrapper<Board>()
                .eq(Board::getChannelId, channelId)
                .isNull(Board::getDeletedAt)
                .orderByAsc(Board::getSort)
                .last("LIMIT 1"));
        return boards.isEmpty() ? null : boards.get(0).getId();
    }

    private List<String> patterns(String keyword) {
        List<String> patterns = new ArrayList<>();
        for (String token : keyword.trim().split("\\s+")) {
            if (!token.isBlank()) {
                patterns.add("%" + token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            }
        }
        if (patterns.isEmpty()) {
            patterns.add("%" + keyword.trim() + "%");
        }
        return patterns;
    }

    private String summarizePosts(String header, List<AiContextPost> posts) {
        if (posts == null || posts.isEmpty()) {
            return header + "：无";
        }
        StringBuilder builder = new StringBuilder(header).append("（").append(posts.size()).append(" 条）：");
        int count = 0;
        for (AiContextPost post : posts) {
            if (count++ >= MAX_SUMMARY_POSTS) {
                break;
            }
            builder.append("\n- id=").append(post.getId())
                    .append(" 标题=").append(nullSafe(post.getTitle()))
                    .append(" 频道=").append(nullSafe(post.getChannelName()))
                    .append(" 赞=").append(post.getLikeCount() == null ? 0 : post.getLikeCount())
                    .append(" 评论=").append(post.getCommentCount() == null ? 0 : post.getCommentCount());
        }
        return builder.toString();
    }

    private String titleOf(PostResponse post) {
        if (post.title() != null && !post.title().isBlank()) {
            return post.title();
        }
        String plain = post.content() == null ? "" : post.content().replaceAll("<[^>]*>", " ");
        return truncate(plain.trim(), 60);
    }

    private String str(Map<String, Object> args, String key, String defaultValue) {
        Object value = args.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private int clampInt(Map<String, Object> args, String key, int defaultValue, int min, int max) {
        Object value = args.get(key);
        int parsed = defaultValue;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                parsed = defaultValue;
            }
        }
        return Math.min(Math.max(parsed, min), max);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /**
     * 代理上下文：谁在行动、人设是什么、允许在哪些频道活动。
     */
    public record AgentContext(String agentUserId, AgentProfile profile, List<String> channelIds) {
    }

    /**
     * 工具执行结果。
     *
     * @param outputType {@code POST} / {@code COMMENT} / {@code LIKE} / {@code NONE}
     * @param outputId   产出内容 id（只读工具为 null）
     * @param summary    给模型/日志看的结果摘要
     */
    public record ToolOutcome(String outputType, String outputId, String summary) {
    }

    /** 与 {@code AgentRun.OutputType} 对齐的字符串常量（避免工具层依赖实体枚举）。 */
    public static final class AgentRunTypes {
        public static final String POST = "POST";
        public static final String COMMENT = "COMMENT";
        public static final String LIKE = "LIKE";
        public static final String NONE = "NONE";

        private AgentRunTypes() {
        }
    }

    /** 仅用于日志：代理可用频道数量（诊断「为什么没动作」）。 */
    public int channelCount(AgentContext context) {
        return context.channelIds() == null ? 0 : context.channelIds().size();
    }

    /** 频道对象（提示词里拼频道名用）。 */
    public Channel channel(String channelId) {
        return channelMapper.selectById(channelId);
    }

    /** 当前 UTC 时间（便于测试替换）。 */
    public LocalDateTime now() {
        return LocalDateTime.now();
    }

    /** 便于日志排查：把参数打成一行。 */
    public String describeArgs(Map<String, Object> args) {
        return new LinkedHashMap<>(args == null ? Map.of() : args).toString();
    }
}
