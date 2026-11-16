package com.isles.agent.search;

import java.util.List;

/**
 * 联网搜索抽象（docs/12 §4 P1）：便于把 SearXNG 换成别的服务，也便于测试注入假实现。
 *
 * <p>约定：**任何一次查询都可能有 1–3 个引擎失败**（google HTML 403、duckduckgo CAPTCHA、brave 限流、
 * sogou 间歇 crash）。所以「结果非空」即视为成功，绝不因为部分引擎失败而整体抛错。
 */
public interface SearchProvider {

    /** 当前生效的实现类型：{@code NONE} / {@code SEARXNG}。 */
    String providerType();

    /** 是否可用（类型非 NONE 且 baseUrl 已配置）。 */
    boolean available();

    /**
     * 执行搜索。
     *
     * @param query     查询词（中文必须 UTF-8 编码，否则会搜出完全无关的内容）
     * @param freshness 时间范围：{@code day} / {@code week} / {@code month} / {@code year}，可为 null
     * @param limit     期望条数（实现内部再按 10 封顶）
     * @return 去重后的结果；失败返回空列表（调用方决定降级策略）
     */
    List<SearchResult> search(String query, String freshness, int limit);
}
