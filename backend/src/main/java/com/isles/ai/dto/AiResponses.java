package com.isles.ai.dto;

import java.time.Instant;
import java.util.List;

/**
 * AI 接口响应模型（docs/05 契约扩展，字段只增不改）。
 */
public final class AiResponses {

    private AiResponses() {
    }

    /**
     * 模型配置（管理端与用户端共用）。API Key 只返回是否已配置与掩码，绝不明文返回。
     */
    public record ModelResponse(
            String id,
            String name,
            String baseUrl,
            String model,
            boolean visionEnabled,
            boolean reasoningEnabled,
            String thinkingMode,
            boolean isDefault,
            boolean enabled,
            Integer timeoutSeconds,
            Integer maxTokens,
            boolean apiKeyConfigured,
            String apiKeyMasked,
            Instant createdAt,
            boolean toolCallEnabled) {
    }

    /**
     * 频道侧可选模型（频道主为助手指定模型用）：只暴露展示必需字段，不含 Base URL / 密钥。
     */
    public record SelectableModelResponse(
            String id,
            String name,
            String model,
            boolean visionEnabled,
            boolean reasoningEnabled,
            boolean isDefault) {
    }

    /** AI 能力状态：前端据此提示「未配置模型」。 */
    public record AiStatusResponse(
            boolean configured,
            int enabledModelCount,
            String defaultModelName,
            String defaultModel,
            String message) {
    }

    public record ConversationResponse(
            String id,
            String scope,
            String channelId,
            String title,
            String modelConfigId,
            int messageCount,
            Instant lastMessageAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 回答引用来源（帖子摘要），点击跳转帖子详情。 */
    public record SourceResponse(
            String id,
            String title,
            String channelId,
            String channelName,
            String excerpt,
            String url) {
    }

    public record MessageResponse(
            String id,
            String conversationId,
            String role,
            String content,
            String reasoningContent,
            List<String> images,
            List<SourceResponse> sources,
            String model,
            Integer tokenIn,
            Integer tokenOut,
            String status,
            Instant createdAt) {
    }

    public record ConnectionTestResponse(
            boolean success,
            long latencyMs,
            List<String> models,
            String message) {
    }

    /** 频道助手对外信息（频道页面板展示用；游客不可见时前端降级为登录提示）。 */
    public record AssistantInfoResponse(
            String channelId,
            boolean configured,
            boolean enabled,
            String assistantUserId,
            String nickname,
            String avatarUrl,
            String bio,
            String persona) {
    }

    /** 频道助手设置（管理端）。 */
    public record AssistantSettingsResponse(
            String channelId,
            String assistantUserId,
            String nickname,
            String avatarUrl,
            String coverUrl,
            String bio,
            String persona,
            boolean enabled,
            boolean summaryDailyEnabled,
            boolean summaryWeeklyEnabled,
            boolean monthlyContributorsEnabled,
            boolean autoPostEnabled,
            boolean autoCommentEnabled,
            boolean autoLikeEnabled,
            boolean autoReplyEnabled,
            Integer dailySummaryHour,
            Integer autoPostPerDay,
            Integer autoCommentPerDay,
            Integer autoLikePerDay,
            Instant updatedAt,
            String modelConfigId) {
    }

    public record TaskLogResponse(
            String id,
            String channelId,
            String taskType,
            String frequencyKey,
            String status,
            String resultText,
            String postId,
            String commentId,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt) {
    }

    public record TaskLogPage(List<TaskLogResponse> items, long total, int page, int size) {
    }

    /** SSE meta 事件：连接建立后先下发回答消息 id、模型与来源引用。 */
    public record StreamMeta(
            String conversationId,
            String messageId,
            String model,
            List<SourceResponse> sources,
            boolean deepThinking) {
    }

    /**
     * SSE sources 事件：工具调用完成后下发的真实来源引用（覆盖 meta 里的兜底引用）。
     *
     * <p>{@code toolRounds} 为实际执行的工具轮次，0 表示模型未调用工具（此时 sources 与 meta 相同）。
     */
    public record StreamSources(List<SourceResponse> sources, int toolRounds) {
    }

    /** SSE delta/reasoning 事件。 */
    public record StreamDelta(String content) {
    }

    /** SSE done 事件：回答结束（正常/停止/失败）。 */
    public record StreamDone(String messageId, boolean aborted, String status, Integer tokenIn, Integer tokenOut) {
    }

    /** SSE error 事件：上游模型/网络失败，前端展示可读错误。 */
    public record StreamError(String code, String message) {
    }

    public record StopResponse(boolean stopped) {
    }
}
