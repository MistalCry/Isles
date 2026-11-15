package com.isles.ai.provider;

import java.util.Map;

/**
 * 工具定义（OpenAI function calling 契约，docs/05 §2.13）。
 *
 * @param name        工具名（模型回传时使用）
 * @param description 给模型看的能力说明，写清楚何时该调用
 * @param parameters  JSON Schema（type=object + properties + required）
 */
public record AiToolDefinition(String name, String description, Map<String, Object> parameters) {

    public AiToolDefinition {
        parameters = parameters == null ? Map.of("type", "object", "properties", Map.of()) : Map.copyOf(parameters);
    }
}
