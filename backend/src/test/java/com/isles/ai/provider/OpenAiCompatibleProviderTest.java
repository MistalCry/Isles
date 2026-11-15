package com.isles.ai.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAI 兼容 Provider 单元测试：使用本机 JDK HttpServer 作为 mock 模型服务，
 * 覆盖非流式、SSE 流式、vision 请求体、重试、鉴权失败、模型列表与连接失败。
 *
 * <p>不依赖 LMStudio、不连接数据库（任务要求：mock HTTP 测试）。
 */
class OpenAiCompatibleProviderTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void should_parse_non_stream_chat_completion() throws IOException {
        String response = """
                {"model":"test-model","choices":[{"message":{"content":"你好，岛民","reasoning_content":"先问候"}}],
                 "usage":{"prompt_tokens":5,"completion_tokens":7}}
                """;
        start(jsonHandler(200, response));
        OpenAiCompatibleProvider provider = provider("http://127.0.0.1:" + port() + "/v1", 1);

        AiChatResult result = provider.chat(AiChatRequest.of("test-model", List.of(AiChatMessage.user("hi")),
                false, false, 128));

        assertThat(result.content()).isEqualTo("你好，岛民");
        assertThat(result.reasoningContent()).isEqualTo("先问候");
        assertThat(result.usage().promptTokens()).isEqualTo(5);
        assertThat(result.usage().completionTokens()).isEqualTo(7);
        assertThat(result.model()).isEqualTo("test-model");
    }

    @Test
    void should_stream_deltas_and_reasoning_until_done() throws IOException {
        String sse = """
                data: {"choices":[{"delta":{"reasoning_content":"想一想"}}]}

                data: {"choices":[{"delta":{"content":"你"}}]}

                data: {"choices":[{"delta":{"content":"好"}}]}

                data: [DONE]

                """;
        start(jsonHandler(200, sse));
        OpenAiCompatibleProvider provider = provider("http://127.0.0.1:" + port() + "/v1", 0);
        List<String> deltas = new ArrayList<>();
        List<String> reasoning = new ArrayList<>();

        provider.streamChat(AiChatRequest.of("test-model", List.of(AiChatMessage.user("hi")), true, true, 128),
                new AiStreamListener() {
                    @Override
                    public void onDelta(String content) {
                        deltas.add(content);
                    }

                    @Override
                    public void onReasoning(String content) {
                        reasoning.add(content);
                    }
                });

        assertThat(deltas).containsExactly("你", "好");
        assertThat(reasoning).containsExactly("想一想");
    }

    @Test
    void should_stop_streaming_when_listener_cancelled() throws IOException {
        String sse = """
                data: {"choices":[{"delta":{"content":"第一段"}}]}

                data: {"choices":[{"delta":{"content":"第二段"}}]}

                data: [DONE]

                """;
        start(jsonHandler(200, sse));
        OpenAiCompatibleProvider provider = provider("http://127.0.0.1:" + port() + "/v1", 0);
        List<String> deltas = new ArrayList<>();

        provider.streamChat(AiChatRequest.of("test-model", List.of(AiChatMessage.user("hi")), true, false, 128),
                new AiStreamListener() {
                    @Override
                    public void onDelta(String content) {
                        deltas.add(content);
                    }

                    @Override
                    public boolean isCancelled() {
                        return !deltas.isEmpty();
                    }
                });

        assertThat(deltas).containsExactly("第一段");
    }

    @Test
    void should_send_vision_content_parts_when_images_provided() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        start(exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, 200, """
                    {"choices":[{"message":{"content":"看到了"}}]}
                    """);
        });
        OpenAiCompatibleProvider provider = provider("http://127.0.0.1:" + port() + "/v1", 0);

        provider.chat(AiChatRequest.of("vision-model",
                List.of(AiChatMessage.user("这张图是什么", List.of("data:image/png;base64,AAAA"))),
                false, false, 128));

        assertThat(capturedBody.get()).contains("\"image_url\"")
                .contains("data:image/png;base64,AAAA")
                .contains("\"type\":\"text\"");
    }

    @Test
    void should_retry_once_when_upstream_returns_500() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        start(exchange -> {
            int attempt = calls.incrementAndGet();
            if (attempt == 1) {
                writeJson(exchange, 500, "{\"error\":{\"message\":\"temporary\"}}");
            } else {
                writeJson(exchange, 200, """
                        {"choices":[{"message":{"content":"重试成功"}}]}
                        """);
            }
        });
        OpenAiCompatibleProvider provider = provider("http://127.0.0.1:" + port() + "/v1", 1);

        AiChatResult result = provider.chat(AiChatRequest.of("test-model", List.of(AiChatMessage.user("hi")),
                false, false, 128));

        assertThat(result.content()).isEqualTo("重试成功");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void should_map_401_to_unauthorized_error_code() throws IOException {
        start(jsonHandler(401, "{\"error\":{\"message\":\"invalid api key\"}}"));
        OpenAiCompatibleProvider provider = provider("http://127.0.0.1:" + port() + "/v1", 0);

        assertThatThrownBy(() -> provider.chat(AiChatRequest.of("test-model",
                List.of(AiChatMessage.user("hi")), false, false, 128)))
                .isInstanceOf(AiProviderException.class)
                .satisfies(ex -> assertThat(((AiProviderException) ex).getCode())
                        .isEqualTo(AiProviderException.UNAUTHORIZED));
    }

    @Test
    void should_list_models_and_test_connection() throws IOException {
        start(jsonHandler(200, """
                {"data":[{"id":"qwen2.5-vl-7b"},{"id":"llama-3.1-8b"}]}
                """));
        OpenAiCompatibleProvider provider = provider("http://127.0.0.1:" + port() + "/v1", 0);

        assertThat(provider.listModels()).containsExactly("qwen2.5-vl-7b", "llama-3.1-8b");
        provider.testConnection();
    }

    @Test
    void should_normalize_base_url_without_v1_segment() throws IOException {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        start(exchange -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            writeJson(exchange, 200, "{\"data\":[]}");
        });
        OpenAiCompatibleProvider provider = provider("http://127.0.0.1:" + port(), 0);

        provider.listModels();

        assertThat(capturedPath.get()).isEqualTo("/v1/models");
    }

    @Test
    void should_map_connection_failure() {
        OpenAiCompatibleProvider provider = provider("http://127.0.0.1:1/v1", 0);

        assertThatThrownBy(() -> provider.chat(AiChatRequest.of("test-model",
                List.of(AiChatMessage.user("hi")), false, false, 128)))
                .isInstanceOf(AiProviderException.class)
                .satisfies(ex -> assertThat(((AiProviderException) ex).getCode())
                        .isEqualTo(AiProviderException.CONNECTION_FAILED));
    }

    /**
     * 深度思考开关必须真正改变请求体：开启下发 high，关闭下发 none。
     *
     * <p>关闭时若不显式下发 none，LM Studio + Qwen3 这类模型会沿用模板默认值继续思考，
     * 用户看到的就是「开关没作用」。
     */
    @Test
    void should_send_reasoning_effort_high_when_deep_thinking_enabled() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        start(exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, 200, """
                    {"choices":[{"message":{"content":"ok"}}]}
                    """);
        });
        OpenAiCompatibleProvider provider = provider("http://127.0.0.1:" + port() + "/v1", 0);

        provider.chat(AiChatRequest.of("reasoning-model", List.of(AiChatMessage.user("hi")),
                false, true, 128, true));

        assertThat(capturedBody.get()).contains("\"reasoning_effort\":\"high\"");
    }

    @Test
    void should_send_reasoning_effort_none_when_deep_thinking_disabled() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        start(exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, 200, """
                    {"choices":[{"message":{"content":"ok"}}]}
                    """);
        });
        OpenAiCompatibleProvider provider = provider("http://127.0.0.1:" + port() + "/v1", 0);

        provider.chat(AiChatRequest.of("reasoning-model", List.of(AiChatMessage.user("hi")),
                false, false, 128, true));

        assertThat(capturedBody.get()).contains("\"reasoning_effort\":\"none\"");
    }

    @Test
    void should_omit_reasoning_effort_for_non_reasoning_model() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        start(exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, 200, """
                    {"choices":[{"message":{"content":"ok"}}]}
                    """);
        });
        OpenAiCompatibleProvider provider = provider("http://127.0.0.1:" + port() + "/v1", 0);

        // 非推理模型：即使前端打开深度思考也不下发 reasoning_effort，避免上游 400
        provider.chat(AiChatRequest.of("plain-model", List.of(AiChatMessage.user("hi")),
                false, true, 128, false));

        assertThat(capturedBody.get()).doesNotContain("reasoning_effort");
    }

    /**
     * 思考模式 = CHAT_TEMPLATE 时，用 chat_template_kwargs.enable_thinking 开关（LM Studio / vLLM + Qwen3）。
     */
    @Test
    void should_send_chat_template_kwargs_when_thinking_mode_is_chat_template() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        start(exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, 200, """
                    {"choices":[{"message":{"content":"ok"}}]}
                    """);
        });
        OpenAiCompatibleProvider provider = provider("http://127.0.0.1:" + port() + "/v1", 0);

        provider.chat(AiChatRequest.of("local-model", List.of(AiChatMessage.user("hi")),
                false, false, 128, true, "CHAT_TEMPLATE"));

        assertThat(capturedBody.get()).contains("chat_template_kwargs");
        assertThat(capturedBody.get()).contains("\"enable_thinking\":false");
        assertThat(capturedBody.get()).doesNotContain("reasoning_effort");
    }


    @Test
    void should_parse_tool_calls_from_non_stream_response() throws IOException {
        String response = """
                {"model":"test-model","choices":[{"finish_reason":"tool_calls","message":{
                  "role":"assistant","content":null,
                  "tool_calls":[{"id":"call_1","type":"function",
                    "function":{"name":"search_channel_posts","arguments":"{\\"query\\":\\"露营\\",\\"limit\\":3}"}}]}}]}
                """;
        start(jsonHandler(200, response));
        OpenAiCompatibleProvider provider = provider("http://127.0.0.1:" + port() + "/v1", 0);

        AiChatResult result = provider.chat(AiChatRequest.of("test-model", List.of(AiChatMessage.user("露营")),
                false, false, 128).withTools(List.of(tool("search_channel_posts"))));

        assertThat(result.hasToolCalls()).isTrue();
        assertThat(result.finishReason()).isEqualTo("tool_calls");
        assertThat(result.toolCalls()).hasSize(1);
        AiToolCall call = result.toolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_1");
        assertThat(call.name()).isEqualTo("search_channel_posts");
        assertThat(call.arguments()).contains("露营");
    }

    @Test
    void should_send_tools_and_merge_streamed_tool_call_fragments() throws IOException {
        // SSE 每个 data 行必须是完整 JSON：分片只体现在 tool_calls 的 arguments 片段上
        String first = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_9\","
                + "\"type\":\"function\",\"function\":{\"name\":\"search_\",\"arguments\":\"{\\\"query\\\":\"}}]}}]}\n\n";
        String second = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"name\":\"channel_posts\",\"arguments\":\"\\\"岛\\\"}\"}}]}}]}\n\n";
        String sse = first + second
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: [DONE]\n\n";
        AtomicReference<String> capturedBody = new AtomicReference<>();
        start(exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, 200, sse);
        });
        OpenAiCompatibleProvider provider = provider("http://127.0.0.1:" + port() + "/v1", 0);
        AtomicReference<List<AiToolCall>> calls = new AtomicReference<>(List.of());

        provider.streamChat(AiChatRequest.of("test-model", List.of(AiChatMessage.user("岛")), true, false, 128)
                        .withTools(List.of(tool("search_channel_posts"))),
                new AiStreamListener() {
                    @Override
                    public void onToolCalls(List<AiToolCall> toolCalls, String finishReason) {
                        calls.set(toolCalls);
                    }
                });

        // 请求体带 tools 与 tool_choice=auto
        assertThat(capturedBody.get()).contains("\"tools\"").contains("\"tool_choice\":\"auto\"");
        assertThat(capturedBody.get()).contains("search_channel_posts");
        // 分片按 index 拼接成一个完整调用
        assertThat(calls.get()).hasSize(1);
        assertThat(calls.get().get(0).id()).isEqualTo("call_9");
        assertThat(calls.get().get(0).name()).isEqualTo("search_channel_posts");
        assertThat(calls.get().get(0).arguments()).isEqualTo("{\"query\":\"岛\"}");
    }

    @Test
    void should_serialize_tool_result_messages() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        start(exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, 200, """
                    {"model":"m","choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}
                    """);
        });
        OpenAiCompatibleProvider provider = provider("http://127.0.0.1:" + port() + "/v1", 0);

        provider.chat(AiChatRequest.of("m", List.of(
                AiChatMessage.user("岛"),
                AiChatMessage.assistantToolCalls(null, List.of(new AiToolCall("call_1", "search_channel_posts", "{}"))),
                AiChatMessage.toolResult("call_1", "{\"results\":[]}")), false, false, 128));

        assertThat(capturedBody.get()).contains("\"role\":\"tool\"")
                .contains("\"tool_call_id\":\"call_1\"")
                .contains("\"tool_calls\"");
    }

    private AiToolDefinition tool(String name) {
        return new AiToolDefinition(name, "测试工具", java.util.Map.of("type", "object"));
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    private OpenAiCompatibleProvider provider(String baseUrl, int maxRetries) {
        return new OpenAiCompatibleProvider(baseUrl, "test-key", 10, maxRetries);
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private void start(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
    }

    private HttpHandler jsonHandler(int status, String body) {
        return exchange -> writeJson(exchange, status, body);
    }

    private void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
