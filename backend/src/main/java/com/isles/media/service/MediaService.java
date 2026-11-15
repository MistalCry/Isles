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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** 读取文件头字节数：ISO BMFF（avif/heic/mp4/mov）需要读到兼容 brand 列表 */
    private static final int HEADER_LENGTH = 32;
    private static final int THUMB_MAX_DIMENSION = 480;
    /**
     * 图片白名单：只收浏览器原生能渲染的格式（用户决策：不做服务端转码）。
     *
     * <p>AVIF / HEIC / HEIF 由 ImageIO 直接解码会失败，走 ffmpeg 兜底；两者都不可用时
     * 降级为「原图直出 + 尺寸未知」，上传本身仍然成功。
     */
    private static final Set<String> IMAGE_MIMES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp",
            "image/avif", "image/bmp", "image/heic", "image/heif");
    private static final Set<String> VIDEO_MIMES = Set.of(
            "video/mp4", "video/webm", "video/quicktime", "video/x-m4v");
    /** ISO BMFF 的 ftyp box 位置（bytes 4..7 = 'ftyp'，8..11 = major brand） */
    private static final Set<String> BRAND_AVIF = Set.of("avif", "avis");
    private static final Set<String> BRAND_HEIC = Set.of("heic", "heix", "hevc", "hevx", "heim", "heis");
    private static final Set<String> BRAND_HEIF = Set.of("mif1", "msf1", "heif");
    private static final Set<String> BRAND_M4V = Set.of("M4V ", "M4VH", "M4VP");
    private static final String BRAND_QUICKTIME = "qt  ";

    /** ffmpeg 输出里的 Duration: 00:00:12.34 */
    private static final Pattern DURATION_PATTERN = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");
    /** ffmpeg 输出里的 1080x1920 */
    private static final Pattern SIZE_PATTERN = Pattern.compile("(\\d{2,5})x(\\d{2,5})");

    private final MediaFileMapper mediaFileMapper;
    private final Path uploadRoot;
    private final String ffmpegBin;

    public MediaService(MediaFileMapper mediaFileMapper,
                        @Value("${app.upload-dir:./uploads}") String uploadDir,
                        @Value("${app.media.ffmpeg:ffmpeg}") String ffmpegBin) {
        this.mediaFileMapper = mediaFileMapper;
        this.ffmpegBin = ffmpegBin;
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
            } else {
                // ImageIO 与 ffmpeg 都无法解码（如未安装 ffmpeg 的 HEIC/AVIF）：
                // 降级为原图直出，尺寸未知；前端按原始宽高比渲染，不影响发布。
                log.info("图片无法解码，降级为原图直出（无缩略图/尺寸） mediaId={} mime={}", mediaId, detectedMime);
            }
        } else {
            // 视频：抽首帧做封面 + 读取时长/分辨率（列表不再显示黑块）
            VideoMeta meta = probeVideo(target);
            if (meta != null) {
                width = meta.width();
                height = meta.height();
                durationMs = meta.durationMs();
            }
            String posterStoragePath = writeVideoPoster(target, directory, mediaId);
            if (posterStoragePath != null) {
                thumbUrl = "/uploads/" + relativeDir + "/" + posterStoragePath;
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
     *
     * <p>ISO BMFF 家族（mp4 / mov / m4v / avif / heic / heif）文件头都是 {@code ftyp}，
     * 必须按 major brand + 兼容 brand 区分，否则 HEIC 会被误判成 mp4。
     *
     * @return 识别出的 MIME；不认识返回 null
     */
    static String detectMime(byte[] header) {
        if (header == null || header.length < 3) {
            return null;
        }
        if ((header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (header.length >= 8 && (header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N'
                && header[3] == 'G') {
            return "image/png";
        }
        if (header[0] == 'G' && header[1] == 'I' && header[2] == 'F' && header[3] == '8') {
            return "image/gif";
        }
        if (header.length >= 4 && header[0] == 'B' && header[1] == 'M') {
            return "image/bmp";
        }
        if (header.length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return "image/webp";
        }
        if (header.length >= 4 && (header[0] & 0xFF) == 0x1A && (header[1] & 0xFF) == 0x45
                && (header[2] & 0xFF) == 0xDF && (header[3] & 0xFF) == 0xA3) {
            return "video/webm";
        }
        if (header.length >= 12 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p') {
            return detectIsoBmffMime(header);
        }
        return null;
    }

    /**
     * 解析 ISO BMFF 的 brand 列表：major brand（8..11）+ compatible brands（16 起每 4 字节）。
     */
    private static String detectIsoBmffMime(byte[] header) {
        List<String> brands = new ArrayList<>(4);
        brands.add(new String(header, 8, 4, StandardCharsets.US_ASCII));
        for (int offset = 16; offset + 4 <= header.length; offset += 4) {
            brands.add(new String(header, offset, 4, StandardCharsets.US_ASCII));
        }
        if (brands.stream().anyMatch(BRAND_AVIF::contains)) {
            return "image/avif";
        }
        if (brands.stream().anyMatch(BRAND_HEIC::contains)) {
            return "image/heic";
        }
        if (brands.stream().anyMatch(BRAND_HEIF::contains)) {
            return "image/heif";
        }
        if (brands.contains(BRAND_QUICKTIME)) {
            return "video/quicktime";
        }
        if (brands.stream().anyMatch(BRAND_M4V::contains)) {
            return "video/x-m4v";
        }
        // isom / mp42 / avc1 等常规 MP4 brand
        return "video/mp4";
    }

    static String extensionFor(String mime) {
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/avif" -> "avif";
            case "image/bmp" -> "bmp";
            case "image/heic" -> "heic";
            case "image/heif" -> "heif";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/quicktime" -> "mov";
            case "video/x-m4v" -> "m4v";
            default -> throw new AppException(ErrorCode.MEDIA_INVALID_TYPE);
        };
    }

    private BufferedImage readImage(Path path) {
        try {
            BufferedImage image = ImageIO.read(path.toFile());
            if (image != null) {
                return image;
            }
        } catch (IOException ex) {
            log.debug("ImageIO 读取失败，尝试 ffmpeg 解码 path={}", path);
        } catch (RuntimeException ex) {
            // 个别 ImageIO 插件对畸形/新格式文件抛运行时异常；不能让它冒泡成 500
            log.debug("ImageIO 解码异常，尝试 ffmpeg 解码 path={} message={}", path, ex.getMessage());
        }
        // ImageIO 默认不支持 WebP/AVIF/HEIC：交给 ffmpeg 解码，保证有尺寸与缩略图
        return readImageViaFfmpeg(path);
    }

    private BufferedImage readImageViaFfmpeg(Path path) {
        Path temp = null;
        try {
            temp = Files.createTempFile("isles-media-", ".png");
            ProcessBuilder builder = new ProcessBuilder(ffmpegBin, "-y", "-i", path.toString(),
                    "-frames:v", "1", temp.toString());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                log.warn("图片解码失败（ffmpeg 不可用或格式不支持） path={}", path);
                return null;
            }
            return ImageIO.read(temp.toFile());
        } catch (IOException ex) {
            log.warn("图片解码失败 path={} message={}", path, ex.getMessage());
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响主流程
                }
            }
        }
    }

    /**
     * 生成缩略图（最大边 480px）。带透明通道的源图输出 PNG，其余输出 JPEG（白底）。
     * 返回相对文件名，失败返回 null。
     */
    private String writeThumbnail(BufferedImage source, Path directory, String mediaId) {
        try {
            int sourceWidth = source.getWidth();
            int sourceHeight = source.getHeight();
            double scale = Math.min(1.0, (double) THUMB_MAX_DIMENSION / Math.max(sourceWidth, sourceHeight));
            int thumbWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
            int thumbHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
            boolean hasAlpha = source.getColorModel().hasAlpha();
            BufferedImage thumbnail = new BufferedImage(thumbWidth, thumbHeight,
                    hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = thumbnail.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                if (!hasAlpha) {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, thumbWidth, thumbHeight);
                }
                graphics.drawImage(source, 0, 0, thumbWidth, thumbHeight, null);
            } finally {
                graphics.dispose();
            }
            String thumbName = mediaId + (hasAlpha ? "_thumb.png" : "_thumb.jpg");
            ImageIO.write(thumbnail, hasAlpha ? "png" : "jpg", directory.resolve(thumbName).toFile());
            return thumbName;
        } catch (IOException | RuntimeException ex) {
            log.warn("缩略图生成失败 mediaId={}", mediaId, ex);
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // 视频探测 / 首帧封面（ffmpeg；不可用时优雅降级）
    // ---------------------------------------------------------------------

    /** 视频元信息（尺寸与时长，探测失败为 null）。 */
    private record VideoMeta(Integer width, Integer height, Long durationMs) {
    }

    /**
     * 用 ffmpeg 读取视频分辨率与时长。ffmpeg 不可用时返回 null，不影响上传本身。
     */
    private VideoMeta probeVideo(Path path) {
        try {
            ProcessBuilder builder = new ProcessBuilder(ffmpegBin, "-hide_banner", "-i", path.toString());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            Integer width = null;
            Integer height = null;
            Matcher size = SIZE_PATTERN.matcher(output);
            if (size.find()) {
                width = Integer.parseInt(size.group(1));
                height = Integer.parseInt(size.group(2));
            }
            Long durationMs = null;
            Matcher duration = DURATION_PATTERN.matcher(output);
            if (duration.find()) {
                long hours = Long.parseLong(duration.group(1));
                long minutes = Long.parseLong(duration.group(2));
                double seconds = Double.parseDouble(duration.group(3));
                durationMs = Math.round((hours * 3600 + minutes * 60 + seconds) * 1000);
            }
            return width == null && durationMs == null ? null : new VideoMeta(width, height, durationMs);
        } catch (IOException ex) {
            log.debug("ffmpeg 不可用，跳过视频探测: {}", ex.getMessage());
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException ex) {
            log.warn("视频探测失败 path={}", path, ex);
            return null;
        }
    }

    /**
     * 抽取视频首帧作为封面（JPEG，最大边 480）。失败返回 null。
     */
    private String writeVideoPoster(Path video, Path directory, String mediaId) {
        String posterName = mediaId + "_poster.jpg";
        Path poster = directory.resolve(posterName);
        if (runFfmpegPoster(video, poster, true) || runFfmpegPoster(video, poster, false)) {
            return posterName;
        }
        return null;
    }

    private boolean runFfmpegPoster(Path video, Path poster, boolean seek) {
        try {
            ProcessBuilder builder = seek
                    ? new ProcessBuilder(ffmpegBin, "-y", "-ss", "0", "-i", video.toString(), "-frames:v", "1",
                            "-vf", "scale='min(480,iw)':-2", poster.toString())
                    : new ProcessBuilder(ffmpegBin, "-y", "-i", video.toString(), "-frames:v", "1",
                            "-vf", "scale='min(480,iw)':-2", poster.toString());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0 && Files.exists(poster) && Files.size(poster) > 0;
        } catch (IOException ex) {
            log.debug("ffmpeg 不可用，跳过视频封面: {}", ex.getMessage());
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
