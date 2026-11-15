package com.isles.analytics.dto;

/**
 * 板块内容分布。
 */
public record BoardStatResponse(String boardId, String boardName, long postCount) {
}
