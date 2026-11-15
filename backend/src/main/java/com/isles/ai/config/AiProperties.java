package com.isles.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 行为参数（application.yml 的 {@code app.ai} 节点）。
 *
 * <p>模型连接信息（Base URL/API Key/模型 ID）存数据库 {@code ai_model_configs}，
 * 由平台管理后台维护；这里只放与部署环境相关的调度、超时、配额参数（docs/03 §4.16）。
 *
 * @param schedulerEnabled  频道助手自动任务调度总开关
 * @param taskCron          调度扫描 cron（Spring 六段，UTC）
 * @param defaultTimeoutSeconds 模型调用默认超时（秒），可被模型配置覆盖
 * @param maxRetries        非流式调用失败重试次数
 * @param streamTimeoutSeconds  SSE 连接最长存活时间（秒）
 * @param contextTopN       检索上下文 TopN
 * @param historyMaxMessages  拼装提示词时携带的历史消息条数上限
 * @param dailyQuota        单用户每日提问配额
 * @param maxConcurrent     单用户并发回答数上限（v1 固定为 1）
 * @param apiKeySecret      API Key 落库加密密钥（为空则明文存储并告警）
 * @param maxContextChars   拼装上下文的最大字符数（粗略 token 控制）
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        boolean schedulerEnabled,
        String taskCron,
        int defaultTimeoutSeconds,
        int maxRetries,
        int streamTimeoutSeconds,
        int contextTopN,
        int historyMaxMessages,
        int dailyQuota,
        int maxConcurrent,
        String apiKeySecret,
        int maxContextChars) {

    /** 单测/非 Spring 场景使用的默认值。 */
    public static AiProperties defaults() {
        return new AiProperties(true, "0 0 * * * *", 60, 1, 120, 8, 20, 200, 1, "", 8000);
    }
}
