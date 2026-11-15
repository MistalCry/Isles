package com.isles.common.security;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * 富文本白名单净化（docs/07 §4）：帖子 HTML 统一经 Jsoup 清洗后入库，
 * 禁止 script/style/事件属性/危险 URL 协议（javascript:、data: 等）。
 *
 * <p><b>为什么必须给 baseUri：</b>Jsoup 在协议校验前会把 URL 解析成绝对地址，
 * baseUri 为空时 {@code /uploads/...} 这类站内相对地址解析失败，整条 {@code src}/{@code href}
 * 会被丢弃（表现为「编辑器里插入的图片/视频刷新后消失」）。这里用一个**仅用于协议判定**的
 * 占位 baseUri，并开启 {@code preserveRelativeLinks}，保证写库的仍是原始相对地址。
 */
public final class HtmlSanitizer {

    /** 仅用于 Jsoup 解析相对地址，不会写入任何数据。 */
    private static final String BASE_URI = "https://isles.invalid/";

    /**
     * 允许的标签与属性：段落、基础排版、标题、列表、引用、链接、图片、视频。
     * 属性仅保留必要项，Jsoup 会同时做协议白名单校验。
     */
    private static final Safelist SAFELIST = Safelist.relaxed()
            .addTags("p", "br", "h1", "h2", "h3", "h4", "s", "u", "video", "source")
            .addAttributes("a", "href", "title")
            .addAttributes("img", "src", "alt", "width", "height", "loading")
            .addAttributes("video", "src", "poster", "controls", "width", "height",
                    "preload", "muted", "loop", "playsinline")
            .addAttributes("source", "src", "type")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https")
            .addProtocols("video", "src", "http", "https")
            .addProtocols("video", "poster", "http", "https")
            .addProtocols("source", "src", "http", "https")
            // 保留 /uploads/... 这类站内相对地址（默认会被转成绝对地址或直接丢弃）
            .preserveRelativeLinks(true);

    private HtmlSanitizer() {
    }

    public static String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return html;
        }
        return Jsoup.clean(html, BASE_URI, SAFELIST);
    }

    /**
     * 提取纯文本（搜索结果摘要、通知摘要等场景），避免把 HTML 标签直接展示给用户。
     */
    public static String toPlainText(String html) {
        if (html == null || html.isBlank()) {
            return html == null ? null : "";
        }
        return Jsoup.parse(html).text();
    }
}
