package com.isles.media.service;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.time.ClockTimes;
import com.isles.media.dto.MediaUploadResponse;
import com.isles.media.entity.MediaFile;
import com.isles.media.mapper.MediaFileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 媒体上传服务（docs/07 §5、FR-MEDIA-01/02/04）。
 *
 * <p>安全校验：类型白名单 + 文件头 magic number（不信任扩展名/Content-Type 声明）、大小限制、
 * ULID 随机文件名、按年月目录存储、禁止路径穿越。图片生成缩略图（ImageIO）；视频 M1 不转码。
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private static final long IMAGE_MAX_BYTES = 10L * 1024 * 1024;
    private static final long VIDEO_MAX_BYTES = 200L * 1024 * 1024;
    private static final int HEADER_LENGTH = 16;
    private static final int THUMB_MAX_DIMENSION = 480;
    private static final Set<String> IMAGE_MIMES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    private static final Set<String> VIDEO_MIMES = Set.of("video/mp4", "video/webm");

    private final MediaFileMapper mediaFileMapper;
    private final Path uploadRoot;

    public MediaService(MediaFileMapper mediaFileMapper,
                        @Value("${app.upload-dir:./uploads}") String uploadDir) {
        this.mediaFileMapper = mediaFileMapper;
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadRoot);
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建上传目录: " + this.uploadRoot, ex);
        }
    }

    /**
     * 上传单个文件。
     *
     * @param type 客户端声明的类别：image / video（与 magic number 交叉校验）
     */
    @Transactional
    public MediaUploadResponse upload(MultipartFile file, String type, String uploaderId) {
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "上传文件不能为空");
        }
        String category = type == null ? "" : type.trim().toLowerCase();
        if (!"image".equals(category) && !"video".equals(category)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "type 必须为 image 或 video");
        }
        byte[] header = readHeader(file);
        String detectedMime = detectMime(header);
        if (detectedMime == null) {
            throw new AppException(ErrorCode.MEDIA_INVALID_TYPE);
        }
        boolean isImage = IMAGE_MIMES.contains(detectedMime);
        if ("image".equals(category) != isImage) {
            throw new AppException(ErrorCode.MEDIA_INVALID_TYPE, "文件真实类型与声明类型不一致");
        }
        long maxBytes = isImage ? IMAGE_MAX_BYTES : VIDEO_MAX_BYTES;
        if (file.getSize() > maxBytes) {
            throw new AppException(ErrorCode.MEDIA_TOO_LARGE,
                    isImage ? "图片不能超过 10MB" : "视频不能超过 200MB");
        }
        String declaredContentType = file.getContentType();
        if (declaredContentType != null && !declaredContentType.isBlank()
                && !"application/octet-stream".equals(declaredContentType)
                && !IMAGE_MIMES.contains(declaredContentType) && !VIDEO_MIMES.contains(declaredContentType)) {
            throw new AppException(ErrorCode.MEDIA_INVALID_TYPE);
        }

        String mediaId = UlidGenerator.generate();
        String extension = extensionFor(detectedMime);
        LocalDateTime now = ClockTimes.nowUtc();
        String relativeDir = String.format("%04d/%02d", now.getYear(), now.getMonthValue());
        Path directory = uploadRoot.resolve(relativeDir).normalize();
        if (!directory.startsWith(uploadRoot)) {
            throw new AppException(ErrorCode.MEDIA_UPLOAD_FAILED);
        }
        Path target = directory.resolve(mediaId + "." + extension);
        try {
            Files.createDirectories(directory);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            log.error("媒体文件写入失败 mediaId={}", mediaId, ex);
            throw new AppException(ErrorCode.MEDIA_UPLOAD_FAILED);
        }

        String storagePath = relativeDir + "/" + mediaId + "." + extension;
        String url = "/uploads/" + storagePath;
        Integer width = null;
        Integer height = null;
        Long durationMs = null;
        String thumbUrl = null;
        if (isImage) {
            BufferedImage image = readImage(target);
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
                String thumbStoragePath = writeThumbnail(image, directory, mediaId);
                if (thumbStoragePath != null) {
                    thumbUrl = "/uploads/" + relativeDir + "/" + thumbStoragePath;
                }
            }
        }

        MediaFile mediaFile = new MediaFile();
        mediaFile.setId(mediaId);
        mediaFile.setUploaderId(uploaderId);
        mediaFile.setMediaType(isImage ? MediaFile.MediaType.IMAGE.name() : MediaFile.MediaType.VIDEO.name());
        mediaFile.setStoragePath(storagePath);
        mediaFile.setUrl(url);
        mediaFile.setThumbUrl(thumbUrl);
        mediaFile.setSizeBytes(file.getSize());
        mediaFile.setWidth(width);
        mediaFile.setHeight(height);
        mediaFile.setDurationMs(durationMs);
        mediaFile.setStatus(MediaFile.Status.ACTIVE.name());
        mediaFile.setCreatedAt(now);
        mediaFileMapper.insert(mediaFile);

        log.info("媒体上传成功 mediaId={} mediaType={} size={}", mediaId, mediaFile.getMediaType(), file.getSize());
        return new MediaUploadResponse(mediaId, url, thumbUrl, mediaFile.getMediaType(), width, height, durationMs);
    }

    private byte[] readHeader(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            byte[] buffer = new byte[HEADER_LENGTH];
            int read = input.read(buffer);
            if (read <= 0) {
                throw new AppException(ErrorCode.MEDIA_INVALID_TYPE);
            }
            byte[] header = new byte[read];
            System.arraycopy(buffer, 0, header, 0, read);
            return header;
        } catch (IOException ex) {
            throw new AppException(ErrorCode.MEDIA_UPLOAD_FAILED);
        }
    }

    /**
     * 依据文件头识别真实类型（docs/07 §5：MIME + magic number，不信任扩展名）。
     */
    private String detectMime(byte[] header) {
        if (header.length >= 3 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (header.length >= 8 && (header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N'
                && header[3] == 'G') {
            return "image/png";
        }
        if (header.length >= 4 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F' && header[3] == '8') {
            return "image/gif";
        }
        if (header.length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return "image/webp";
        }
        if (header.length >= 8 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p') {
            return "video/mp4";
        }
        if (header.length >= 4 && (header[0] & 0xFF) == 0x1A && (header[1] & 0xFF) == 0x45
                && (header[2] & 0xFF) == 0xDF && (header[3] & 0xFF) == 0xA3) {
            return "video/webm";
        }
        return null;
    }

    private String extensionFor(String mime) {
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            default -> throw new AppException(ErrorCode.MEDIA_INVALID_TYPE);
        };
    }

    private BufferedImage readImage(Path path) {
        try {
            return ImageIO.read(path.toFile());
        } catch (IOException ex) {
            // WebP 等 ImageIO 不支持的格式：保留原图，尺寸/缩略图为空
            log.warn("图片解析失败，跳过尺寸与缩略图 path={}", path);
            return null;
        }
    }

    /**
     * 生成缩略图（最大边 480px，JPEG）。返回相对文件名，失败返回 null。
     */
    private String writeThumbnail(BufferedImage source, Path directory, String mediaId) {
        try {
            int sourceWidth = source.getWidth();
            int sourceHeight = source.getHeight();
            double scale = Math.min(1.0, (double) THUMB_MAX_DIMENSION / Math.max(sourceWidth, sourceHeight));
            int thumbWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
            int thumbHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
            BufferedImage thumbnail = new BufferedImage(thumbWidth, thumbHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = thumbnail.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, thumbWidth, thumbHeight);
                graphics.drawImage(source, 0, 0, thumbWidth, thumbHeight, null);
            } finally {
                graphics.dispose();
            }
            String thumbName = mediaId + "_thumb.jpg";
            ImageIO.write(thumbnail, "jpg", directory.resolve(thumbName).toFile());
            return thumbName;
        } catch (IOException | RuntimeException ex) {
            log.warn("缩略图生成失败 mediaId={}", mediaId, ex);
            return null;
        }
    }
}
