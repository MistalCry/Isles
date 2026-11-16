package com.isles.agent.search;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.platform.setting.PlatformSettingService;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * 网页正文抓取（工具 {@code fetch_page}，docs/12 §4 P1）。
 *
 * <p><b>SSRF 防护</b>（docs/07 §5.2）：只允许 http/https，且必须同时满足
 * <ol>
 *   <li>主机名不是 {@code localhost} / {@code *.localhost} / {@code *.local} / {@code *.internal}；</li>
 *   <li>字面量 IP 不落在回环、私有（10/8、172.16/12、192.168/16）、链路本地（169.254/16）、
 *       运营商级 NAT（100.64/10）、{@code 0.0.0.0}；</li>
 *   <li>DNS 解析出的**每个**地址也满足上一条（防「公网域名解析到内网 IP」的绕过）；</li>
 *   <li><b>不跟随重定向</b>：3xx 直接拒绝，避免「公网地址 302 到内网」绕过前两条。</li>
 * </ol>
 *
 * <p>正文用 Jsoup 去标签后压成一行，最多 {@value #MAX_TEXT_LENGTH} 字；响应体最多读 2MB，
 * 防止大文件把内存打满。
 */
@Component
@RequiredArgsConstructor
public class PageFetcher {

    private static final Logger log = LoggerFactory.getLogger(PageFetcher.class);

    /** 正文上限（docs/12 §4：限 5000 字）。 */
    public static final int MAX_TEXT_LENGTH = 5000;
    /** 响应体读取上限 2MB。 */
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final PlatformSettingService settingService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * 抓取并抽取正文。
     *
     * @throws AppException {@link ErrorCode#AGENT_FETCH_BLOCKED}（内网/非法协议/重定向）或
     *                      {@link ErrorCode#AGENT_FETCH_FAILED}（网络失败/非 HTML）
     */
    public String fetch(String rawUrl) {
        URI uri = parseAndCheck(rawUrl);
        int timeoutSeconds = timeoutSeconds();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9")
                    .header("User-Agent", "IslesAgent/1.0 (+https://isles.local)")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                throw new AppException(ErrorCode.AGENT_FETCH_BLOCKED, "不跟随重定向（防 SSRF）：HTTP " + status);
            }
            if (status != 200) {
                throw new AppException(ErrorCode.AGENT_FETCH_FAILED, "目标站点返回 HTTP " + status);
            }
            String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
            if (!contentType.isEmpty() && !contentType.contains("html") && !contentType.contains("text/plain")) {
                throw new AppException(ErrorCode.AGENT_FETCH_FAILED, "只支持 HTML/纯文本，实际类型：" + contentType);
            }
            byte[] body;
            try (InputStream stream = response.body()) {
                body = stream.readNBytes(MAX_BYTES);
            }
            String text = extractText(new String(body, StandardCharsets.UTF_8));
            if (text.isEmpty()) {
                throw new AppException(ErrorCode.AGENT_FETCH_FAILED, "页面没有可读正文（可能是纯 JS 渲染）");
            }
            return text;
        } catch (IOException ex) {
            log.warn("网页抓取失败 url={} reason={}", uri, ex.toString());
            throw new AppException(ErrorCode.AGENT_FETCH_FAILED, "抓取失败：" + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AppException(ErrorCode.AGENT_FETCH_FAILED, "抓取被中断");
        }
    }

    /**
     * 解析并做 SSRF 校验（包可见，便于单测直接调用）。
     */
    URI parseAndCheck(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "fetch_page 需要 url");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "url 格式非法");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new AppException(ErrorCode.AGENT_FETCH_BLOCKED, "只允许 http/https：" + rawUrl);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new AppException(ErrorCode.AGENT_FETCH_BLOCKED, "url 缺少主机名");
        }
        if (isBlockedHostname(host)) {
            throw new AppException(ErrorCode.AGENT_FETCH_BLOCKED, "不允许抓取内网/本机地址：" + host);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw new AppException(ErrorCode.AGENT_FETCH_FAILED, "域名解析失败：" + host);
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new AppException(ErrorCode.AGENT_FETCH_BLOCKED, "不允许抓取内网/本机地址：" + host);
            }
        }
        return uri;
    }

    /**
     * 主机名黑名单（字面量判断，DNS 结果另判）。
     */
    static boolean isBlockedHostname(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(value) || value.endsWith(".localhost")) {
            return true;
        }
        if (value.endsWith(".local") || value.endsWith(".internal") || "metadata".equals(value)) {
            return true;
        }
        // 字面量 IP 直接按地址判断（避免走 DNS）
        if (value.startsWith("[") && value.endsWith("]")) {
            return isBlockedLiteral(value.substring(1, value.length() - 1));
        }
        if (value.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            return isBlockedLiteral(value);
        }
        return false;
    }

    private static boolean isBlockedLiteral(String literal) {
        try {
            return isBlockedAddress(InetAddress.getByName(literal));
        } catch (UnknownHostException ex) {
            // 解析不了的字面量（如 999.1.1.1）按拒绝处理，避免歧义
            return true;
        }
    }

    /**
     * 地址黑名单：回环、私有、链路本地、运营商级 NAT、任意地址、组播。
     */
    static boolean isBlockedAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            // 100.64.0.0/10（运营商级 NAT）
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
            // 0.0.0.0/8
            if (first == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * HTML → 纯文本：去标签、压空白、截断到 {@value #MAX_TEXT_LENGTH} 字。
     *
     * <p>独立成静态方法便于单测（不依赖真实网络）。
     */
    static String extractText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = Jsoup.parse(html).text().replaceAll("\\s+", " ").trim();
        return truncate(text, MAX_TEXT_LENGTH);
    }

    private int timeoutSeconds() {
        int value = settingService.getInt("agent.search.timeoutSeconds");
        return value <= 0 ? 8 : Math.min(value, 60);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
