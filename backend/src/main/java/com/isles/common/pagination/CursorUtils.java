package com.isles.common.pagination;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;

/**
 * 游标分页工具。
 *
 * <p>两种游标：
 * <ul>
 *   <li>时间序列表：游标即最后一条记录的 ULID（字典序 = 时间序）；</li>
 *   <li>热度序列表：游标编码为 {@code score_id}（热度分值 + ULID），
 *       查询条件为 {@code score < ? OR (score = ? AND id < ?)}，保证稳定翻页。</li>
 * </ul>
 */
public final class CursorUtils {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private CursorUtils() {
    }

    /**
     * 归一化分页大小：默认 20，上限 100，非法值按默认处理。
     */
    public static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * 校验 ULID 游标；null 表示从头开始。非法游标返回 400 而非静默忽略，避免前端分页错乱。
     */
    public static String validateIdCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        if (!UlidGenerator.isValid(cursor)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "cursor 格式非法");
        }
        return cursor;
    }

    /**
     * 热度游标（score + ULID）。
     */
    public record HotCursor(long score, String id) {
    }

    public static HotCursor parseHotCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        int separator = cursor.lastIndexOf('_');
        if (separator <= 0 || separator == cursor.length() - 1) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "cursor 格式非法");
        }
        try {
            long score = Long.parseLong(cursor.substring(0, separator));
            String id = cursor.substring(separator + 1);
            if (!UlidGenerator.isValid(id)) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "cursor 格式非法");
            }
            return new HotCursor(score, id);
        } catch (NumberFormatException ex) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "cursor 格式非法");
        }
    }

    public static String formatHotCursor(long score, String id) {
        return score + "_" + id;
    }
}
