package com.isles.ai.provider;

import java.util.List;

/**
 * 发给模型的一条消息。
 *
 * <p>role 取值：{@code system} / {@code user} / {@code assistant} / {@code tool}。
 * <ul>
 *   <li>图片以 URL 或 data URI 形式随用户消息一起发送（多模态），由 {@code AiImageResolver} 预先转换；</li>
 *   <li>助手发起工具调用时 {@code toolCalls} 非空（{@code content} 可为 null）；</li>
 *   <li>工具执行结果用 {@code tool} 角色回填，{@code toolCallId} 必须对应请求里的 id。</li>
 * </ul>
 */
public record AiChatMessage(String role, String content, List<String> imageUrls, String toolCallId,
                            List<AiToolCall> toolCalls) {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    public AiChatMessage {
        imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** 兼容旧调用：无工具调用、非工具结果消息。 */
    public AiChatMessage(String role, String content, List<String> imageUrls) {
        this(role, content, imageUrls, null, List.of());
    }

    public static AiChatMessage system(String content) {
        return new AiChatMessage(ROLE_SYSTEM, content, List.of());
    }

    public static AiChatMessage user(String content) {
        return new AiChatMessage(ROLE_USER, content, List.of());
    }

    public static AiChatMessage user(String content, List<String> imageUrls) {
        return new AiChatMessage(ROLE_USER, content, imageUrls);
    }

    public static AiChatMessage assistant(String content) {
        return new AiChatMessage(ROLE_ASSISTANT, content, List.of());
    }

    /** 助手发起工具调用的消息（回填到下一轮请求中）。 */
    public static AiChatMessage assistantToolCalls(String content, List<AiToolCall> toolCalls) {
        return new AiChatMessage(ROLE_ASSISTANT, content, List.of(), null, toolCalls);
    }

    /** 工具执行结果消息。 */
    public static AiChatMessage toolResult(String toolCallId, String content) {
        return new AiChatMessage(ROLE_TOOL, content, List.of(), toolCallId, List.of());
    }

    public boolean hasImages() {
        return !imageUrls.isEmpty();
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public boolean isToolResult() {
        return ROLE_TOOL.equals(role);
    }
}
