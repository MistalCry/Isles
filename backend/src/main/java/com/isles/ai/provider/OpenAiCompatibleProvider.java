package com.isles.ai.provider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * OpenAI 兼容协议实现（LMStudio / vLLM / Ollama-OpenAI / OpenAI 等）。
 *
 * <p>能力：
 * <ul>
 *   <li>chat completions 非流式与流式（SSE 解析，使用 {@code BodyHandlers.ofLines()}）；</li>
 *   <li>vision：图片以 {@code image_url} content part 发送（base64 data URI 或公网 URL）；</li>
 *   <li>深度思考：{@code reasoning_effort} 参数 + 解析 {@code reasoning_content} 增量；</li>
 *   <li>超时、非流式重试、稳定错误码；模型列表与连通性测试。</li>
 * </ul>
 *
 * <p>线程安全：无共享可变状态，由 {@link AiProviderFactory} 按配置创建。
 */
public class OpenAiCompatibleProvider implements AiProvider {

    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int MAX_ERROR_BODY_CHARS = 500;
    private static final long RETRY_BACKOFF_MILLIS = 300L;
    private static final String DONE_MARKER = "[DONE]";
    private static final String REASONING_PARAM = "reasoning_effort";
    private static final String REASONING_HIGH = "high";
    /**
     * 关闭思考必须显式下发 none。
     *
     * <p>LM Studio（以及多数 OpenAI 兼容的推理模型服务）在缺省时不保证关闭思考：
     * Qwen3 系列的模板默认就会先输出思考内容，表现为「深度思考开关无效」。
     */
    private static final String REASONING_NONE = "none";
    private static final String CHAT_TEMPLATE_PARAM = "chat_template_kwargs";
    private static final String THINKING_REASONING_EFFORT = "REASONING_EFFORT";
    private static final String THINKING_CHAT_TEMPLATE = "CHAT_TEMPLATE";
    private static final String THINKING_BOTH = "BOTH";

    private final String baseUrl;
    private final String apiKey;
    private final int defaultTimeoutSeconds;
    private final int maxRetries;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleProvider(String baseUrl, String apiKey, int defaultTimeoutSeconds, int maxRetries) {
        this(baseUrl, apiKey, defaultTimeoutSeconds, maxRetries, JsonMapper.builder().build());
    }

    public OpenAiCompatibleProvider(String baseUrl, String apiKey, int defaultTimeoutSeconds, int maxRetries,
                                    JsonMapper jsonMapper) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.trim();
        this.defaultTimeoutSeconds = defaultTimeoutSeconds <= 0 ? 60 : defaultTimeoutSeconds;
        this.maxRetries = Math.max(0, maxRetries);
        this.jsonMapper = jsonMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .build();
    }

    @Override
    public AiChatResult chat(AiChatRequest request) {
        String body = buildBody(request, false);
        HttpRequest httpRequest = requestBuilder("/chat/completions", "application/json",
                resolveTimeout(request), body);
        HttpResponse<String> response = sendWithRetry(httpRequest);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw statusException(response.statusCode(), response.body());
        }
        JsonNode root = parseJson(response.body());
        if (root.has("error")) {
            throw new AiProviderException(AiProviderException.UPSTREAM_ERROR, upstreamErrorMessage(root));
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            throw new AiProviderException(AiProviderException.UPSTREAM_ERROR, "模型返回内容为空");
        }
        JsonNode message = choices.path(0).path("message");
        String content = textOf(message.path("content"));
        String reasoning = message.has("reasoning_content")
                ? textOf(message.path("reasoning_content"))
                : textOf(message.path("reasoning"));
        return new AiChatResult(content == null ? "" : content, reasoning, parseUsage(root.path("usage")),
                textOf(root.path("model")));
    }

    @Override
    public void streamChat(AiChatRequest request, AiStreamListener listener) {
        String body = buildBody(request, true);
        HttpRequest httpRequest = requestBuilder("/chat/completions", "text/event-stream",
                resolveTimeout(request), body);
        HttpResponse<Stream<String>> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
        } catch (HttpTimeoutException ex) {
            throw new AiProviderException(AiProviderException.TIMEOUT, "模型服务响应超时", ex);
        } catch (IOException ex) {
            throw connectionException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AiProviderException(AiProviderException.INTERNAL_ERROR, "模型调用被中断", ex);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errorBody;
            try (Stream<String> errorLines = response.body()) {
                errorBody = errorLines.limit(20).collect(Collectors.joining("\n"));
            }
            throw statusException(response.statusCode(), errorBody);
        }
        AiUsage usage = AiUsage.empty();
        try (Stream<String> lines = response.body()) {
            Iterator<String> iterator = lines.iterator();
            while (iterator.hasNext()) {
                if (listener.isCancelled()) {
                    return;
                }
                String line = iterator.next();
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if (DONE_MARKER.equals(payload)) {
                    break;
                }
                JsonNode node = parseJsonSafely(payload);
                if (node == null) {
                    continue;
                }
                if (node.has("error")) {
                    throw new AiProviderException(AiProviderException.UPSTREAM_ERROR, upstreamErrorMessage(node));
                }
                JsonNode choices = node.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    JsonNode delta = choices.path(0).path("delta");
                    String content = textOf(delta.path("content"));
                    if (content != null && !content.isEmpty()) {
                        listener.onDelta(content);
                    }
                    String reasoning = delta.has("reasoning_content")
                            ? textOf(delta.path("reasoning_content"))
                            : textOf(delta.path("reasoning"));
                    if (reasoning != null && !reasoning.isEmpty()) {
                        listener.onReasoning(reasoning);
                    }
                }
                if (node.has("usage") && !node.path("usage").isNull()) {
                    usage = parseUsage(node.path("usage"));
                }
            }
        }
        if (listener.isCancelled()) {
            return;
        }
        listener.onCompleted(usage);
    }

    @Override
    public List<String> listModels() {
        HttpRequest httpRequest = requestBuilder("/models", "application/json", defaultTimeoutSeconds, null);
        HttpResponse<String> response = sendWithRetry(httpRequest);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw statusException(response.statusCode(), response.body());
        }
        JsonNode root = parseJson(response.body());
        List<String> models = new ArrayList<>();
        JsonNode data = root.path("data");
        if (data.isArray()) {
            for (JsonNode item : data) {
                String id = textOf(item.path("id"));
                if (id != null && !id.isBlank()) {
                    models.add(id);
                }
            }
        }
        return models;
    }

    @Override
    public void testConnection() {
        listModels();
    }

    // ---------------------------------------------------------------------
    // 请求构造
    // ---------------------------------------------------------------------

    private String buildBody(AiChatRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("stream", stream);
        body.put("messages", toWireMessages(request.messages()));
        if (request.maxTokens() > 0) {
            body.put("max_tokens", request.maxTokens());
        }
        if (request.reasoningCapable()) {
            // 不同后端开关思考的方式不同，按模型配置下发；关闭时必须显式传 false/none，
            // 否则模型会沿用模板默认继续思考（表现为「深度思考开关无效」）。
            String mode = request.thinkingMode() == null ? THINKING_REASONING_EFFORT : request.thinkingMode();
            boolean useEffort = THINKING_REASONING_EFFORT.equals(mode) || THINKING_BOTH.equals(mode);
            boolean useChatTemplate = THINKING_CHAT_TEMPLATE.equals(mode) || THINKING_BOTH.equals(mode);
            if (useEffort) {
                body.put(REASONING_PARAM, request.deepThinking() ? REASONING_HIGH : REASONING_NONE);
            }
            if (useChatTemplate) {
                body.put(CHAT_TEMPLATE_PARAM, Map.of("enable_thinking", request.deepThinking()));
            }
        }
        try {
            return jsonMapper.writeValueAsString(body);
        } catch (RuntimeException ex) {
            throw new AiProviderException(AiProviderException.INTERNAL_ERROR, "构造模型请求失败", ex);
        }
    }

    private List<Map<String, Object>> toWireMessages(List<AiChatMessage> messages) {
        List<Map<String, Object>> wire = new ArrayList<>(messages.size());
        for (AiChatMessage message : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.role());
            if (message.hasImages()) {
                List<Map<String, Object>> parts = new ArrayList<>();
                if (message.content() != null && !message.content().isBlank()) {
                    parts.add(Map.of("type", "text", "text", message.content()));
                }
                for (String imageUrl : message.imageUrls()) {
                    parts.add(Map.of("type", "image_url", "image_url", Map.of("url", imageUrl)));
                }
                item.put("content", parts);
            } else {
                item.put("content", message.content() == null ? "" : message.content());
            }
            wire.add(item);
        }
        return wire;
    }

    private HttpRequest requestBuilder(String path, String accept, int timeoutSeconds, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Accept", accept)
                .header("Content-Type", "application/json");
        if (apiKey != null) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        if (body == null) {
            builder.GET();
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    private int resolveTimeout(AiChatRequest request) {
        if (request.timeoutSeconds() != null && request.timeoutSeconds() > 0) {
            return request.timeoutSeconds();
        }
        return defaultTimeoutSeconds;
    }

    // ---------------------------------------------------------------------
    // 响应处理
    // ---------------------------------------------------------------------

    private HttpResponse<String> sendWithRetry(HttpRequest request) {
        AiProviderException lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 500 && attempt < maxRetries) {
                    lastError = statusException(response.statusCode(), response.body());
                    sleepBackoff(attempt);
                    continue;
                }
                return response;
            } catch (HttpTimeoutException ex) {
                lastError = new AiProviderException(AiProviderException.TIMEOUT, "模型服务响应超时", ex);
                if (attempt < maxRetries) {
                    sleepBackoff(attempt);
                }
            } catch (IOException ex) {
                lastError = connectionException(ex);
                if (attempt < maxRetries) {
                    sleepBackoff(attempt);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AiProviderException(AiProviderException.INTERNAL_ERROR, "模型调用被中断", ex);
            }
        }
        throw lastError == null
                ? new AiProviderException(AiProviderException.UPSTREAM_ERROR, "模型调用失败")
                : lastError;
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS * (attempt + 1));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AiProviderException(AiProviderException.INTERNAL_ERROR, "模型调用被中断", ex);
        }
    }

    private AiProviderException connectionException(IOException ex) {
        if (ex instanceof ConnectException) {
            return new AiProviderException(AiProviderException.CONNECTION_FAILED,
                    "无法连接模型服务，请确认 LMStudio/OpenAI 兼容服务已启动且 Base URL 正确", ex);
        }
        return new AiProviderException(AiProviderException.CONNECTION_FAILED, "模型服务连接失败", ex);
    }

    private AiProviderException statusException(int status, String body) {
        String snippet = truncate(body == null ? "" : body.trim(), MAX_ERROR_BODY_CHARS);
        if (status == 401 || status == 403) {
            return new AiProviderException(AiProviderException.UNAUTHORIZED,
                    "模型服务鉴权失败，请检查 API Key 配置");
        }
        String message = "模型服务返回错误（HTTP " + status + "）";
        if (!snippet.isEmpty()) {
            message = message + "：" + snippet;
        }
        return new AiProviderException(AiProviderException.UPSTREAM_ERROR, message);
    }

    private JsonNode parseJson(String body) {
        try {
            return jsonMapper.readTree(body == null ? "" : body);
        } catch (RuntimeException ex) {
            throw new AiProviderException(AiProviderException.UPSTREAM_ERROR, "模型服务返回了无法解析的数据", ex);
        }
    }

    private JsonNode parseJsonSafely(String payload) {
        try {
            return jsonMapper.readTree(payload);
        } catch (RuntimeException ex) {
            // 部分服务会发送 keep-alive/注释行；单行解析失败不应中断整个流
            return null;
        }
    }

    private AiUsage parseUsage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return AiUsage.empty();
        }
        return new AiUsage(usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0));
    }

    private String upstreamErrorMessage(JsonNode node) {
        JsonNode error = node.path("error");
        if (error.isObject()) {
            String message = textOf(error.path("message"));
            if (message != null && !message.isBlank()) {
                return "模型服务错误：" + truncate(message, MAX_ERROR_BODY_CHARS);
            }
        }
        return "模型服务错误：" + truncate(node.toString(), MAX_ERROR_BODY_CHARS);
    }

    private String textOf(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isString() ? node.asString() : node.asString(null);
    }

    private static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AiProviderException(AiProviderException.MODEL_NOT_CONFIGURED, "模型 Base URL 未配置");
        }
        String value = raw.trim().replaceAll("/+$", "");
        // 未包含 /v1 等版本段时补全（LMStudio 常见两个写法：http://localhost:1234 与 .../v1）
        if (!value.matches(".*/v\\d+$")) {
            value = value + "/v1";
        }
        return value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
    }
}
