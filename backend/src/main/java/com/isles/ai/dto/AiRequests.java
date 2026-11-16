package com.isles.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * AI 接口请求体（docs/05 契约扩展，字段只增不改）。按用途归组，避免大量单字段文件。
 */
public final class AiRequests {

    private AiRequests() {
    }

    /**
     * 新建会话：
     * <ul>
     *   <li>{@code GLOBAL}：全站频道问问；</li>
     *   <li>{@code CHANNEL}：频道内问答，必须带 channelId（频道可见即可）；</li>
     *   <li>{@code CHANNEL_ASSISTANT}：与频道助手的私聊多会话，必须带 channelId 且用户须为频道成员。</li>
     * </ul>
     */
    public record CreateConversationRequest(
            @Pattern(regexp = "GLOBAL|CHANNEL|CHANNEL_ASSISTANT",
                    message = "scope 取值必须为 GLOBAL、CHANNEL 或 CHANNEL_ASSISTANT")
            String scope,

            String channelId,

            @Size(max = 120, message = "标题最长 120 个字符")
            String title,

            String modelConfigId) {
    }

    public record RenameConversationRequest(
            @NotBlank(message = "标题不能为空")
            @Size(max = 120, message = "标题最长 120 个字符")
            String title) {
    }

    /**
     * 发送提问：regenerate=true 时表示「重新生成」（不新增用户消息，复用最后一条提问）。
     */
    public record SendMessageRequest(
            @NotBlank(message = "提问内容不能为空")
            @Size(max = 8000, message = "提问最长 8000 个字符")
            String content,

            @Size(max = 4, message = "最多上传 4 张图片")
            List<String> imageUrls,

            String modelConfigId,

            boolean deepThinking,

            boolean regenerate) {
    }

    /** 编辑历史提问并重发：后端截断该消息之后的全部消息再生成回答。 */
    public record EditMessageRequest(
            @NotBlank(message = "提问内容不能为空")
            @Size(max = 8000, message = "提问最长 8000 个字符")
            String content,

            String modelConfigId,

            boolean deepThinking) {
    }

    public record RegenerateRequest(String modelConfigId, boolean deepThinking) {
    }

    /** 平台后台模型配置：apiKey 为空/掩码值表示保持原密钥不变。 */
    public record ModelConfigRequest(
            @NotBlank(message = "配置名称不能为空")
            @Size(max = 64, message = "配置名称最长 64 个字符")
            String name,

            @NotBlank(message = "Base URL 不能为空")
            @Size(max = 500, message = "Base URL 最长 500 个字符")
            String baseUrl,

            @Size(max = 500, message = "API Key 最长 500 个字符")
            String apiKey,

            @NotBlank(message = "模型 ID 不能为空")
            @Size(max = 128, message = "模型 ID 最长 128 个字符")
            String model,

            boolean visionEnabled,

            boolean reasoningEnabled,

            @Pattern(regexp = "REASONING_EFFORT|CHAT_TEMPLATE|BOTH|NONE", message = "思考模式取值非法")
            String thinkingMode,

            boolean isDefault,

            boolean enabled,

            @Min(value = 1, message = "超时至少 1 秒")
            @Max(value = 600, message = "超时最多 600 秒")
            Integer timeoutSeconds,

            @Min(value = 1, message = "最大 token 至少 1")
            @Max(value = 128_000, message = "最大 token 超出上限")
            Integer maxTokens,

            /** 是否启用工具调用（function calling）；默认关闭时问答走关键词检索。 */
            boolean toolCallEnabled) {
    }

    /** 未保存配置时的连通性测试。 */
    public record TestConnectionRequest(
            @NotBlank(message = "Base URL 不能为空")
            @Size(max = 500, message = "Base URL 最长 500 个字符")
            String baseUrl,

            @Size(max = 500, message = "API Key 最长 500 个字符")
            String apiKey,

            @Size(max = 128, message = "模型 ID 最长 128 个字符")
            String model) {
    }

    /** 频道助手设置（persona + 任务开关/频率）。 */
    public record AssistantSettingsRequest(
            @Size(max = 32, message = "昵称最长 32 个字符")
            String nickname,

            @Size(max = 500, message = "头像地址最长 500 个字符")
            String avatarUrl,

            @Size(max = 500, message = "封面地址最长 500 个字符")
            String coverUrl,

            @Size(max = 500, message = "简介最长 500 个字符")
            String bio,

            @Size(max = 2000, message = "设定描述最长 2000 个字符")
            String persona,

            boolean enabled,

            boolean summaryDailyEnabled,

            boolean summaryWeeklyEnabled,

            boolean monthlyContributorsEnabled,

            boolean autoPostEnabled,

            boolean autoCommentEnabled,

            boolean autoLikeEnabled,

            boolean autoReplyEnabled,

            @Min(value = 0, message = "小时取值 0-23")
            @Max(value = 23, message = "小时取值 0-23")
            Integer dailySummaryHour,

            @Min(value = 0, message = "每日发帖上限不能为负")
            @Max(value = 50, message = "每日发帖上限最多 50")
            Integer autoPostPerDay,

            @Min(value = 0, message = "每日评论上限不能为负")
            @Max(value = 100, message = "每日评论上限最多 100")
            Integer autoCommentPerDay,

            @Min(value = 0, message = "每日点赞上限不能为负")
            @Max(value = 200, message = "每日点赞上限最多 200")
            Integer autoLikePerDay,

            /**
             * 频道助手使用的模型配置：{@code null}=不修改，空串=跟随平台默认，非空=指定模型。
             */
            String modelConfigId) {
    }

    /** 手动触发频道助手任务（管理端使用）。 */
    public record RunTaskRequest(
            @NotBlank(message = "任务类型不能为空")
            @Pattern(regexp = "DAILY_SUMMARY|WEEKLY_SUMMARY|MONTHLY_CONTRIBUTORS|AUTO_POST|AUTO_COMMENT|AUTO_LIKE",
                    message = "任务类型非法")
            String taskType) {
    }
}
