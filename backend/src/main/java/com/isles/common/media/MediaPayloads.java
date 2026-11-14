package com.isles.common.media;

import com.isles.common.dto.MediaItem;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;

import java.util.ArrayList;
import java.util.List;

/**
 * 媒体引用校验：发帖/评论时对客户端提交的媒体数组做白名单校验（docs/07 §4）。
 *
 * <p>只接受本地上传路径（/uploads/...）或 http(s) 地址，禁止 javascript:/data: 等危险协议；
 * 类型只允许 IMAGE/VIDEO；最多 9 个。
 */
public final class MediaPayloads {

    public static final int MAX_MEDIA_COUNT = 9;
    private static final int MAX_URL_LENGTH = 500;

    private MediaPayloads() {
    }

    public static List<MediaItem> normalize(List<MediaItem> media) {
        if (media == null || media.isEmpty()) {
            return List.of();
        }
        if (media.size() > MAX_MEDIA_COUNT) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "媒体数量超过上限");
        }
        List<MediaItem> normalized = new ArrayList<>(media.size());
        for (MediaItem item : media) {
            if (item == null) {
                continue;
            }
            String type = item.type() == null ? "" : item.type().toUpperCase();
            if (!"IMAGE".equals(type) && !"VIDEO".equals(type)) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "媒体类型必须为 IMAGE 或 VIDEO");
            }
            String url = requireSafeUrl(item.url(), "媒体地址");
            String thumbUrl = item.thumbUrl() == null ? null : requireSafeUrl(item.thumbUrl(), "缩略图地址");
            normalized.add(new MediaItem(type, url, thumbUrl, item.width(), item.height(), item.durationMs()));
        }
        return normalized;
    }

    /**
     * 媒体 URL 只允许站内相对路径或 http(s)，防止 javascript: 等协议注入。
     */
    public static String requireSafeUrl(String url, String fieldName) {
        if (url == null || url.isBlank() || url.length() > MAX_URL_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, fieldName + "不合法");
        }
        if (url.startsWith("/") && !url.startsWith("//")) {
            return url;
        }
        if (url.startsWith("https://") || url.startsWith("http://")) {
            return url;
        }
        throw new AppException(ErrorCode.VALIDATION_ERROR, fieldName + "必须以 / 或 http(s):// 开头");
    }
}
