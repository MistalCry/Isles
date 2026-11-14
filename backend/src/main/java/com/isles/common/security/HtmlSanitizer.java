package com.isles.common.security;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * 富文本白名单净化（docs/07 §4）：帖子 HTML 统一经 Jsoup 清洗后入库，
 * 禁止 script/style/事件属性/危险 URL 协议（javascript:、data: 等）。
 */
public final class HtmlSanitizer {

    /**
     * 允许的标签与属性：段落、基础排版、标题、列表、引用、链接、图片。
     * 属性仅保留 href/src/alt，Jsoup 会同时协议白名单校验。
     */
    private static final Safelist SAFELIST = Safelist.relaxed()
            .addTags("p", "br", "h1", "h2", "h3", "h4", "s", "u")
            .addAttributes("a", "href", "title")
            .addAttributes("img", "src", "alt", "width", "height")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https");

    private HtmlSanitizer() {
    }

    public static String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return html;
        }
        return Jsoup.clean(html, SAFELIST);
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
