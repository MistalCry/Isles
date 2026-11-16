package com.isles.agent.search;

/**
 * 一条联网搜索结果（docs/12 §4 工具 {@code web_search}）。
 *
 * @param title         标题
 * @param url           链接（去重键）
 * @param content       摘要（已截断到 300 字，避免把整页塞进提示词）
 * @param engine        命中的引擎名（google cse / 360search / yahoo …）
 * @param publishedDate 发布时间（上游未给则为 null）
 */
public record SearchResult(String title, String url, String content, String engine, String publishedDate) {
}
