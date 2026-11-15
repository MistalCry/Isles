package com.isles.ai.provider;

/**
 * 模型调用异常：携带稳定错误码（SSE error 事件与前端提示使用）。
 *
 * <p>HTTP 层业务异常仍走 {@code ErrorCode}；这里只覆盖上游模型服务与流式协议的失败。
 */
public class AiProviderException extends RuntimeException {

    /** 未配置任何可用模型（平台后台未配置 / 全部禁用）。 */
    public static final String MODEL_NOT_CONFIGURED = "AI_MODEL_NOT_CONFIGURED";
    /** 模型服务鉴权失败（401/403）。 */
    public static final String UNAUTHORIZED = "AI_UNAUTHORIZED";
    /** 模型服务连接失败（未启动/地址错误）。 */
    public static final String CONNECTION_FAILED = "AI_CONNECTION_FAILED";
    /** 模型服务超时。 */
    public static final String TIMEOUT = "AI_TIMEOUT";
    /** 模型服务返回错误或非法响应。 */
    public static final String UPSTREAM_ERROR = "AI_UPSTREAM_ERROR";
    /** 服务端内部错误（如线程池拒绝）。 */
    public static final String INTERNAL_ERROR = "AI_INTERNAL_ERROR";

    private final String code;

    public AiProviderException(String code, String message) {
        super(message);
        this.code = code;
    }

    public AiProviderException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
