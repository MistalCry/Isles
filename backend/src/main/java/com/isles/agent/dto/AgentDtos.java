package com.isles.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * AI 代理管理接口模型（V13 / docs/05 §2.15、docs/12 §7）。
 */
public final class AgentDtos {

    private AgentDtos() {
    }

    /**
     * 代理视图：人设 + 账号 + 今日产出 + 最近运行，后台列表一屏够用。
     */
    public record AgentView(
            String id,
            String userId,
            String username,
            String displayName,
            String bio,
            List<String> interests,
            String tone,
            List<List<Integer>> activeHours,
            int postsPerDay,
            int commentsPerDay,
            List<String> channels,
            List<String> toolsEnabled,
            String status,
            Instant lastRunAt,
            long todayPosts,
            long todayComments,
            String lastRunStatus,
            /** 头像地址（存 {@code users.avatar_url}，与社区其它地方共用一张图）。 */
            String avatarUrl,
            /** 社区里展示的昵称（{@code users.nickname}，与 {@code displayName} 同步）。 */
            String nickname) {
    }

    /**
     * 创建/更新人设。创建时 {@code username} 可空（自动生成 {@code agent_xxxxxxxx}）。
     */
    public record SaveAgentRequest(
            @Size(min = 3, max = 32, message = "用户名长度需在 3-32 之间")
            @Pattern(regexp = "^[a-zA-Z0-9_]*$", message = "用户名只能包含字母、数字与下划线")
            String username,

            @NotBlank(message = "展示名不能为空")
            @Size(max = 32, message = "展示名最长 32 个字符")
            String displayName,

            @Size(max = 200, message = "简介最长 200 个字符")
            String bio,

            @Size(max = 10, message = "兴趣标签最多 10 个")
            List<@Size(max = 20, message = "单个兴趣标签最长 20 个字符") String> interests,

            @Pattern(regexp = "casual|enthusiastic|dry", message = "语气取值必须为 casual、enthusiastic 或 dry")
            String tone,

            /** 活跃时段，形如 [[20,24],[0,1]]；空=全天。 */
            List<List<Integer>> activeHours,

            @Min(value = 0, message = "日发帖上限不能为负")
            @Max(value = 20, message = "日发帖上限最多 20")
            Integer postsPerDay,

            @Min(value = 0, message = "日评论上限不能为负")
            @Max(value = 50, message = "日评论上限最多 50")
            Integer commentsPerDay,

            @Size(max = 20, message = "允许频道最多 20 个")
            List<String> channels,

            @Size(max = 20, message = "工具列表最多 20 个")
            List<String> toolsEnabled,

            @Pattern(regexp = "ACTIVE|PAUSED", message = "状态取值必须为 ACTIVE 或 PAUSED")
            String status,

            /**
             * 头像地址（走现有媒体上传，前端给 URL）。创建/更新时同步写 {@code users.avatar_url}，
             * 空串表示清空头像。
             */
            @Size(max = 500, message = "头像地址最长 500 个字符")
            String avatarUrl) {
    }

    /**
     * 运行日志视图（可回放：planJson / toolCallsJson 原样返回）。
     */
    public record RunView(
            String id,
            Instant startedAt,
            Instant finishedAt,
            String outputType,
            String outputId,
            String status,
            String error,
            int tokensIn,
            int tokensOut,
            String planJson,
            String toolCallsJson) {
    }

    public record RunPage(List<RunView> items, long total, int page, int size) {
    }

    /**
     * 手动触发结果：{@code run} 可能为 null（被熔断/限额跳过时只有 status 与 reason）。
     */
    public record RunNowResponse(String status, String reason, RunView run) {
    }
}
