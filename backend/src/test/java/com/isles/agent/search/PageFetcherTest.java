package com.isles.agent.search;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.platform.setting.PlatformSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 网页抓取单元测试：SSRF 黑名单与正文抽取（不依赖真实网络）。
 */
class PageFetcherTest {

    private PageFetcher fetcher;

    @BeforeEach
    void setUp() {
        fetcher = new PageFetcher(mock(PlatformSettingService.class));
    }

    @Test
    void should_block_loopback_and_private_addresses() {
        assertBlocked("http://127.0.0.1/admin");
        assertBlocked("http://127.0.0.1:8080/actuator/health");
        assertBlocked("http://localhost:8888/search");
        assertBlocked("http://localhost/");
        assertBlocked("http://10.0.0.5/");
        assertBlocked("http://192.168.1.1/");
        assertBlocked("http://172.16.0.1/");
        assertBlocked("http://172.31.255.254/");
        assertBlocked("http://169.254.169.254/latest/meta-data/");
        assertBlocked("http://100.64.0.1/");
        assertBlocked("http://0.0.0.0/");
        assertBlocked("http://[::1]/");
        assertBlocked("http://metadata/");
        assertBlocked("http://db.internal/");
    }

    @Test
    void should_block_non_http_schemes() {
        assertBlocked("file:///etc/passwd");
        assertBlocked("ftp://example.com/x");
        assertBlocked("gopher://example.com/");
    }

    @Test
    void should_allow_public_ip_literal() {
        // 公网 IP 字面量不触发 DNS，可在离线环境稳定断言
        assertThat(fetcher.parseAndCheck("http://8.8.8.8/").getHost()).isEqualTo("8.8.8.8");
        assertThat(fetcher.parseAndCheck("https://1.1.1.1/dns").getHost()).isEqualTo("1.1.1.1");
    }

    @Test
    void should_reject_blank_and_malformed_url() {
        assertThatThrownBy(() -> fetcher.parseAndCheck("   "))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> fetcher.parseAndCheck("not a url"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void should_strip_tags_and_collapse_whitespace() {
        String html = "<html><head><title>t</title><script>var a=1</script></head>"
                + "<body><h1>红石</h1><p>红石是  矿物，\n用于传输信号。</p></body></html>";

        String text = PageFetcher.extractText(html);

        assertThat(text).contains("红石").contains("用于传输信号");
        assertThat(text).doesNotContain("<p>").doesNotContain("var a=1");
        assertThat(text).doesNotContain("\n");
    }

    @Test
    void should_truncate_text_to_5000_chars() {
        String html = "<p>" + "红".repeat(6000) + "</p>";

        String text = PageFetcher.extractText(html);

        assertThat(text).hasSize(5001); // 5000 + 省略号
        assertThat(text).endsWith("…");
    }

    @Test
    void should_return_empty_for_blank_html() {
        assertThat(PageFetcher.extractText(null)).isEmpty();
        assertThat(PageFetcher.extractText("   ")).isEmpty();
    }

    private void assertBlocked(String url) {
        assertThatThrownBy(() -> fetcher.parseAndCheck(url))
                .as("应拒绝 %s", url)
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isIn(ErrorCode.AGENT_FETCH_BLOCKED, ErrorCode.VALIDATION_ERROR,
                                ErrorCode.AGENT_FETCH_FAILED));
    }
}
