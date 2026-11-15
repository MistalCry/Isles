package com.isles.ai.provider;

import java.util.List;

/**
 * 发给模型的一条消息（role: system/user/assistant）。
 *
 * <p>图片以 URL 或 data URI 形式随用户消息一起发送（多模态），由 {@code AiImageResolver} 预先转换。
 */
public record AiChatMessage(String role, String content, List<String> imageUrls) {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public AiChatMessage {
        imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
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

    public boolean hasImages() {
        return !imageUrls.isEmpty();
    }
}
