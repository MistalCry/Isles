package com.isles.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 图片解析：把客户端提交的图片引用转换成模型可用的内容。
 *
 * <p>支持三种输入：
 * <ul>
 *   <li>{@code http(s)://...}：公网可访问 URL，原样透传（模型服务自行拉取）；</li>
 *   <li>{@code data:image/...;base64,...}：已编码的 data URI，原样透传；</li>
 *   <li>{@code /uploads/...}：本地上传文件，读取磁盘后转 base64 data URI（本地模型通常无法访问相对路径）。</li>
 * </ul>
 * 不可解析的引用会被跳过（降级为纯文本问答，docs/03 FR-AIQA-04）。
 */
@Component
public class AiImageResolver {

    private static final Logger log = LoggerFactory.getLogger(AiImageResolver.class);

    private static final int MAX_IMAGES = 4;
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final String UPLOAD_PREFIX = "/uploads/";

    private final Path uploadRoot;

    public AiImageResolver(@Value("${app.upload-dir:./uploads}") String uploadDir) {
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    /**
     * 批量解析，自动去重、限制数量；无法解析的引用被跳过。
     */
    public List<String> resolveAll(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return List.of();
        }
        List<String> resolved = new ArrayList<>();
        for (String raw : imageUrls) {
            if (resolved.size() >= MAX_IMAGES) {
                break;
            }
            String value = resolveOne(raw);
            if (value != null && !resolved.contains(value)) {
                resolved.add(value);
            }
        }
        return resolved;
    }

    /**
     * 单个解析；无法解析返回 null。
     */
    public String resolveOne(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("data:image/")) {
            return value;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        if (!value.startsWith(UPLOAD_PREFIX)) {
            log.debug("忽略无法识别的 AI 图片引用");
            return null;
        }
        String relative = value.substring(UPLOAD_PREFIX.length());
        Path path = uploadRoot.resolve(relative).normalize();
        if (!path.startsWith(uploadRoot)) {
            // 防路径穿越
            return null;
        }
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_IMAGE_BYTES) {
                return null;
            }
            String mime = mimeFor(path.getFileName().toString().toLowerCase(Locale.ROOT));
            if (mime == null) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(path);
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException ex) {
            log.warn("读取 AI 图片失败 path={}", path.getFileName());
            return null;
        }
    }

    private String mimeFor(String fileName) {
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (fileName.endsWith(".png")) {
            return "image/png";
        }
        if (fileName.endsWith(".gif")) {
            return "image/gif";
        }
        if (fileName.endsWith(".webp")) {
            return "image/webp";
        }
        return null;
    }
}
