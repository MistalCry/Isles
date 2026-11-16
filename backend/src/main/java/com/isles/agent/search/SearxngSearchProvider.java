package com.isles.agent.search;

import com.isles.platform.setting.PlatformSettingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SearXNG 搜索实现（docs/12 §4 P1）。
 *
 * <p>接口：{@code GET {baseUrl}/search?q=&format=json&engines=&language=}，解析 {@code results[]}。
 *
 * <p>几个必须守住的细节：
 * <ul>
 *   <li><b>查询词一律 UTF-8 百分号编码</b>：Windows 上 curl 会按 GBK 发中文，搜「我的世界」会返回汾酒；
 *       Java 的 {@link URLEncoder#encode(String, java.nio.charset.Charset)} 显式指定 UTF-8，不依赖平台默认编码。</li>
 *   <li><b>部分引擎失败不算失败</b>：只要求 {@code results} 非空；空结果交给调用方降级到站内检索。</li>
 *   <li><b>超时 8s、失败重试 1 次</b>：只对网络异常/5xx/解析失败重试，4xx 直接放弃（重试也没用）。</li>
 *   <li><b>同 url 去重、content 截断 300 字、最多 10 条</b>：提示词预算有限。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SearxngSearchProvider implements SearchProvider {

    private static final Logger log = LoggerFactory.getLogger(SearxngSearchProvider.class);

    public static final String TYPE_NONE = "NONE";
    public static final String TYPE_SEARXNG = "SEARXNG";

    /** 单次最多给模型多少条（docs/12 §4）。 */
    private static final int MAX_RESULTS = 10;
    /** 摘要截断长度。 */
    private static final int MAX_CONTENT_LENGTH = 300;
    /** 语言参数：{@code all} 让实例按查询词自行判断，中英文混用最稳。 */
    private static final String LANGUAGE = "all";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** 连接超时固定 5s（请求超时由配置项控制，默认 8s）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final PlatformSettingService settingService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public String providerType() {
        String value = settingService.getString("agent.search.provider");
        return value == null || value.isBlank() ? TYPE_NONE : value.trim().toUpperCase(Locale.ROOT);
    }

    @Override
    public boolean available() {
        return TYPE_SEARXNG.equals(providerType()) && !baseUrl().isBlank();
    }

    @Override
    public List<SearchResult> search(String query, String freshness, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (!available()) {
            return List.of();
        }
        URI uri = buildUri(query, freshness);
        if (uri == null) {
            return List.of();
        }
        int capped = Math.min(Math.max(limit, 1), MAX_RESULTS);
        JsonNode root = fetchWithRetry(uri);
        if (root == null) {
            return List.of();
        }
        return parseResults(root, capped);
    }

    /**
     * 组装请求 URI。查询词与 engines 都按 UTF-8 编码；engines 为空则不带该参数。
     */
    URI buildUri(String query, String freshness) {
        String base = baseUrl();
        if (base.isBlank()) {
            return null;
        }
        StringBuilder url = new StringBuilder(base);
        if (url.charAt(url.length() - 1) == '/') {
            url.setLength(url.length() - 1);
        }
        url.append("/search?format=json&language=").append(LANGUAGE);
        url.append("&q=").append(encode(query));
        String engines = engines();
        if (!engines.isBlank()) {
            url.append("&engines=").append(encode(engines));
        }
        String timeRange = toTimeRange(freshness);
        if (timeRange != null) {
            url.append("&time_range=").append(timeRange);
        }
        try {
            return URI.create(url.toString());
        } catch (IllegalArgumentException ex) {
            log.warn("SearXNG 地址非法 baseUrl={}", base);
            return null;
        }
    }

    private JsonNode fetchWithRetry(URI uri) {
        int timeoutSeconds = timeoutSeconds();
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("Accept", "application/json")
                        // SearXNG 对没有 UA 的请求可能直接拒绝
                        .header("User-Agent", "IslesAgent/1.0 (+https://isles.local)")
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 500) {
                    log.warn("SearXNG 返回 {}（第 {} 次）q={}", response.statusCode(), attempt, uri.getQuery());
                    continue;
                }
                if (response.statusCode() != 200) {
                    // 4xx 重试没有意义（JSON 未开启、路径写错等），直接放弃
                    log.warn("SearXNG 返回 {}，放弃本次搜索", response.statusCode());
                    return null;
                }
                JsonNode root = MAPPER.readTree(response.body());
                if (root == null || !root.has("results")) {
                    log.warn("SearXNG 响应缺少 results 字段（第 {} 次）", attempt);
                    continue;
                }
                return root;
            } catch (IOException ex) {
                log.warn("SearXNG 请求失败（第 {} 次）q={} reason={}", attempt, queryOf(uri), ex.toString());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            } catch (RuntimeException ex) {
                log.warn("SearXNG 响应解析失败（第 {} 次）reason={}", attempt, ex.toString());
            }
        }
        return null;
    }

    /**
     * 解析 results[]：按 url 去重、截断摘要、按条数封顶。
     *
     * <p>引擎名逐个保留（{@code engine} 字段），便于日志观察「哪几个引擎在挂」。
     */
    List<SearchResult> parseResults(JsonNode root, int limit) {
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return List.of();
        }
        Map<String, SearchResult> deduped = new LinkedHashMap<>();
        for (JsonNode item : results) {
            String url = text(item, "url");
            if (url == null || url.isBlank()) {
                continue;
            }
            if (deduped.size() >= limit) {
                break;
            }
            String key = normalizeUrl(url);
            if (deduped.containsKey(key)) {
                continue;
            }
            String published = text(item, "publishedDate");
            if (published == null || published.isBlank()) {
                published = text(item, "pubdate");
            }
            deduped.put(key, new SearchResult(
                    text(item, "title"),
                    url,
                    truncate(text(item, "content"), MAX_CONTENT_LENGTH),
                    text(item, "engine"),
                    published));
        }
        return new ArrayList<>(deduped.values());
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    String baseUrl() {
        String value = settingService.getString("agent.search.baseUrl");
        return value == null ? "" : value.trim();
    }

    String engines() {
        String value = settingService.getString("agent.search.engines");
        if (value == null || value.isBlank()) {
            return "";
        }
        // 只规整全角逗号与逗号两侧空白：引擎名本身可能带空格（如 "google cse"），
        // 绝不能把空格当分隔符，否则 "google cse" 会被拆成两个不存在的引擎。
        String normalized = value.replace('，', ',');
        List<String> parts = new ArrayList<>();
        for (String part : normalized.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return String.join(",", parts);
    }

    int timeoutSeconds() {
        int value = settingService.getInt("agent.search.timeoutSeconds");
        return value <= 0 ? 8 : Math.min(value, 60);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 显式 UTF-8：绝不使用平台默认编码（Windows 上是 GBK，中文查询会直接搜错）。
     */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String toTimeRange(String freshness) {
        if (freshness == null) {
            return null;
        }
        return switch (freshness.trim().toLowerCase(Locale.ROOT)) {
            case "day", "d" -> "day";
            case "week", "w" -> "week";
            case "month", "m" -> "month";
            case "year", "y" -> "year";
            default -> null;
        };
    }

    private static String normalizeUrl(String url) {
        String value = url.trim();
        int hash = value.indexOf('#');
        if (hash > 0) {
            value = value.substring(0, hash);
        }
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.isString() ? value.asString() : value.toString();
        return text.isBlank() ? null : text;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String queryOf(URI uri) {
        String query = uri.getQuery();
        return query == null ? "" : query.length() > 80 ? query.substring(0, 80) : query;
    }
}
