package com.isles.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 富文本净化单元测试（docs/07 §4、docs/00 §6.2）。
 *
 * <p>核心回归点：站内相对地址（{@code /uploads/...}）必须原样保留。
 * Jsoup 在 baseUri 为空时会把相对地址解析失败，从而把 {@code src}/{@code href} 整条属性丢掉，
 * 表现为「富文本里插入的图片/视频刷新后消失」。
 */
class HtmlSanitizerTest {

    @Test
    void should_keep_relative_upload_urls_for_img_and_video() {
        String html = """
                <p>看这张图</p>
                <img src="/uploads/2026/11/a.jpg" alt="图" width="800" height="600">
                <video src="/uploads/2026/11/v.mp4" poster="/uploads/2026/11/p.jpg" controls width="640"></video>
                <a href="/channels/abc">站内链接</a>
                """;

        String cleaned = HtmlSanitizer.sanitize(html);

        assertThat(cleaned).contains("src=\"/uploads/2026/11/a.jpg\"");
        assertThat(cleaned).contains("alt=\"图\"");
        assertThat(cleaned).contains("<video");
        assertThat(cleaned).contains("src=\"/uploads/2026/11/v.mp4\"");
        assertThat(cleaned).contains("poster=\"/uploads/2026/11/p.jpg\"");
        assertThat(cleaned).contains("controls");
        assertThat(cleaned).contains("href=\"/channels/abc\"");
    }

    @Test
    void should_keep_absolute_http_media() {
        String cleaned = HtmlSanitizer.sanitize(
                "<img src=\"https://cdn.example.com/a.png\"><a href=\"https://example.com/x\">x</a>");

        assertThat(cleaned).contains("src=\"https://cdn.example.com/a.png\"");
        assertThat(cleaned).contains("href=\"https://example.com/x\"");
    }

    @Test
    void should_strip_dangerous_protocols() {
        String cleaned = HtmlSanitizer.sanitize("""
                <img src="javascript:alert(1)">
                <a href="javascript:alert(2)">x</a>
                <img src="data:image/svg+xml;base64,PHN2Zz4=">
                """);

        assertThat(cleaned).doesNotContain("javascript:");
        assertThat(cleaned).doesNotContain("data:image");
    }

    @Test
    void should_strip_script_style_and_event_handlers() {
        String cleaned = HtmlSanitizer.sanitize("""
                <script>alert(1)</script>
                <style>body{display:none}</style>
                <img src="/uploads/a.jpg" onerror="alert(3)" onload="alert(4)">
                <a href="/x" onclick="steal()">x</a>
                """);

        assertThat(cleaned).doesNotContain("<script");
        assertThat(cleaned).doesNotContain("<style");
        assertThat(cleaned).doesNotContain("onerror");
        assertThat(cleaned).doesNotContain("onload");
        assertThat(cleaned).doesNotContain("onclick");
        // 安全属性仍然保留
        assertThat(cleaned).contains("src=\"/uploads/a.jpg\"");
    }

    @Test
    void should_keep_basic_typography_tags() {
        String cleaned = HtmlSanitizer.sanitize(
                "<h2>标题</h2><p><strong>粗</strong><em>斜</em><u>下划线</u></p><ul><li>项</li></ul><blockquote>引用</blockquote>");

        assertThat(cleaned).contains("<h2>标题</h2>");
        assertThat(cleaned).contains("<strong>粗</strong>");
        assertThat(cleaned).contains("<li>项</li>");
        assertThat(cleaned).contains("<blockquote>");
        assertThat(cleaned).contains("引用");
    }

    @Test
    void should_return_null_or_blank_unchanged() {
        assertThat(HtmlSanitizer.sanitize(null)).isNull();
        assertThat(HtmlSanitizer.sanitize("   ")).isEqualTo("   ");
    }

    @Test
    void should_extract_plain_text() {
        assertThat(HtmlSanitizer.toPlainText("<p>你好 <strong>世界</strong></p>")).isEqualTo("你好 世界");
        assertThat(HtmlSanitizer.toPlainText(null)).isNull();
    }
}
