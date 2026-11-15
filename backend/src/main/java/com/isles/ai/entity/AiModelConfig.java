package com.isles.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * AI 模型配置（表 {@code ai_model_configs}，docs/03 FR-PADM-01）。
 *
 * <p>API Key 永不出现在接口响应中；{@code apiKeyEncrypted=1} 表示使用 AES-GCM 密文存储。
 */
@Getter
@Setter
@TableName("ai_model_configs")
public class AiModelConfig {

    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String baseUrl;
    private String apiKey;
    private Boolean apiKeyEncrypted;
    private String model;
    private Boolean visionEnabled;
    private Boolean reasoningEnabled;
    /** 思考参数下发方式：REASONING_EFFORT / CHAT_TEMPLATE / BOTH / NONE（V5） */
    private String thinkingMode;
    private Boolean isDefault;
    private Boolean enabled;
    private Integer timeoutSeconds;
    private Integer maxTokens;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
