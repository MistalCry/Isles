package com.isles.agent.search;

import com.isles.platform.setting.PlatformSettingService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SearXNG 搜索实现单元测试：中文 UTF-8 编码、去重、截断、条数封顶、重试与「部分引擎失败仍算成功」。
 */
class SearxngSearchProviderTest {

    private HttpServer server;
    private PlatformSettingService settingService;
    private SearxngSearchProvider provider;
    private final AtomicReference<String> capturedQuery = new AtomicReference<>();
    private final AtomicReference<String> capturedRaw = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        settingService = mock(PlatformSettingService.class);
        when(settingService.getString("agent.search.provider")).thenReturn("SEARXNG");
        when(settingService.getString("agent.search.engines")).thenReturn("");
        when(settingService.getInt("agent.search.timeoutSeconds")).thenReturn(8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        when(settingService.getString("agent.search.baseUrl"))
                .thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
        provider = new SearxngSearchProvider(settingService);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void should_send_chinese_query_as_utf8_and_parse_results() {
        // 关键回归：中文必须按 UTF-8 编码。GBK 会让「我的世界」搜出「汾酒」这类完全无关的结果。
        respond(200, """
                {"results":[
                  {"title":"红石矿石 - 我的世界","url":"https://mc.example.com/redstone","content":"红石基础",
                   "engine":"google cse","publishedDate":"2026-11-01T00:00:00"},
                  {"title":"红石教程","url":"https://zh.example.com/p/1","content":"红石电路入门","engine":"360search"}
                ]}
                """);

        List<SearchResult> results = provider.search("我的世界 红石", null, 5);

        assertThat(capturedQuery.get()).isEqualTo("我的世界 红石");
        // 原始 query 串里必须是 UTF-8 百分号编码，而不是 GBK 的双字节
        assertThat(capturedRaw.get()).contains("%E6%88%91%E7%9A%84%E4%B8%96%E7%95%8C");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).engine()).isEqualTo("google cse");
        assertThat(results.get(0).publishedDate()).isEqualTo("2026-11-01T00:00:00");
    }

    @Test
    void should_dedupe_by_url_and_cap_at_ten() {
        StringBuilder body = new StringBuilder("{\"results\":[");
        for (int i = 0; i < 15; i++) {
            if (i > 0) {
                body.append(',');
            }
            // i==1 与 i==0 是同一个 url（大小写 + 尾斜杠不同），应被合并；其余 url 各不相同
            String url = i == 1 ? "https://DUP.example.com/a0/" : "https://dup.example.com/a" + i;
            body.append("{\"title\":\"t").append(i).append("\",\"url\":\"").append(url)
                    .append("\",\"content\":\"c\",\"engine\":\"yahoo\"}");
        }
        body.append("]}");
        respond(200, body.toString());

        List<SearchResult> results = provider.search("dup", null, 50);

        // 15 条里有 1 条重复 → 去重后 14 条，再按上限截到 10 条
        assertThat(results).hasSize(10);
        assertThat(results.stream().map(SearchResult::url).distinct().count()).isEqualTo(10);
        // 去重发生在截断之前：重复项不会占掉名额
        assertThat(results.get(0).url()).isEqualTo("https://dup.example.com/a0");
        assertThat(results.get(1).url()).isEqualTo("https://dup.example.com/a2");
    }

    @Test
    void should_truncate_content_to_300_chars() {
        String longContent = "红".repeat(500);
        respond(200, "{\"results\":[{\"title\":\"t\",\"url\":\"https://a.example.com\",\"content\":\""
                + longContent + "\",\"engine\":\"google cse\"}]}");

        List<SearchResult> results = provider.search("长文", null, 3);

        assertThat(results.get(0).content()).hasSize(301); // 300 字 + 省略号
        assertThat(results.get(0).content()).endsWith("…");
    }

    @Test
    void should_treat_partial_engine_failure_as_success() {
        // 真实实例常见形态：google HTML 403 / ddg CAPTCHA，但仍有 360search 与 yahoo 的结果
        respond(200, """
                {"results":[{"title":"ok","url":"https://ok.example.com","content":"c","engine":"360search"}]}
                """);

        List<SearchResult> results = provider.search("partial", null, 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).engine()).isEqualTo("360search");
    }

    @Test
    void should_retry_once_on_server_error() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/search", exchange -> {
            int attempt = calls.incrementAndGet();
            if (attempt == 1) {
                write(exchange, 500, "{}");
                return;
            }
            write(exchange, 200,
                    "{\"results\":[{\"title\":\"t\",\"url\":\"https://r.example.com\",\"content\":\"c\","
                            + "\"engine\":\"yahoo\"}]}");
        });

        List<SearchResult> results = provider.search("retry", null, 5);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(results).hasSize(1);
    }

    @Test
    void should_not_retry_on_client_error() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/search", exchange -> {
            calls.incrementAndGet();
            write(exchange, 403, "{\"error\":\"json disabled\"}");
        });

        List<SearchResult> results = provider.search("denied", null, 5);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(results).isEmpty();
    }

    @Test
    void should_omit_engines_param_when_not_configured() {
        when(settingService.getString("agent.search.engines")).thenReturn("");
        respond(200, "{\"results\":[]}");

        URI uri = provider.buildUri("x", null);

        assertThat(uri.getQuery()).doesNotContain("engines=");
        assertThat(uri.getQuery()).contains("format=json");
    }

    @Test
    void should_pass_engines_and_time_range_when_configured() {
        when(settingService.getString("agent.search.engines")).thenReturn("google cse，360search, yahoo");

        URI uri = provider.buildUri("x", "week");

        // getRawQuery 才是实际发出去的串：必须已百分号编码（空格不能裸奔）
        assertThat(uri.getRawQuery())
                .contains("engines=" + SearxngSearchProvider.encode("google cse,360search,yahoo"))
                .contains("time_range=week")
                .doesNotContain(" ");
        // 引擎名里的空格要保留（"google cse" 是一个引擎，不是两个）
        assertThat(uri.getQuery()).contains("engines=google cse,360search,yahoo");
    }

    @Test
    void should_return_empty_when_provider_disabled() {
        when(settingService.getString("agent.search.provider")).thenReturn("NONE");

        assertThat(provider.available()).isFalse();
        assertThat(provider.search("x", null, 5)).isEmpty();
    }

    private void respond(int status, String body) {
        server.createContext("/search", exchange -> {
            capturedRaw.set(exchange.getRequestURI().getRawQuery());
            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    if (pair.startsWith("q=")) {
                        capturedQuery.set(URLDecoder.decode(pair.substring(2), StandardCharsets.UTF_8));
                    }
                }
            }
            write(exchange, status, body);
        });
    }

    private void write(HttpExchange exchange, int status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
