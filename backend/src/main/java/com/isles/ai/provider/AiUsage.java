package com.isles.ai.provider;

/**
 * token 用量（上游未返回时为 0，由服务层按字符数粗略估算）。
 */
public record AiUsage(int promptTokens, int completionTokens) {

    public static AiUsage empty() {
        return new AiUsage(0, 0);
    }

    public int total() {
        return promptTokens + completionTokens;
    }

    public AiUsage plus(AiUsage other) {
        if (other == null) {
            return this;
        }
        return new AiUsage(promptTokens + other.promptTokens, completionTokens + other.completionTokens);
    }
}
