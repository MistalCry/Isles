package com.isles.media.service;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.media.dto.MediaUploadResponse;
import com.isles.media.entity.MediaFile;
import com.isles.media.mapper.MediaFileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 媒体类型识别与降级测试（docs/07 §5）。
 *
 * <p>覆盖：新增的 avif/bmp/heic/heif/mov/m4v 白名单、ISO BMFF brand 区分、
 * 以及 ffmpeg 不可用时图片上传「原图直出 + 尺寸未知」不报 500。
 */
class MediaServiceTest {

    @TempDir
    Path uploadDir;

    private MediaFileMapper mediaFileMapper;
    private MediaService mediaService;

    @BeforeEach
    void setUp() {
        mediaFileMapper = mock(MediaFileMapper.class);
        when(mediaFileMapper.insert(org.mockito.ArgumentMatchers.<MediaFile>any())).thenReturn(1);
        // 故意指向不存在的可执行文件：模拟「服务器没有 ffmpeg」
        mediaService = new MediaService(mediaFileMapper, uploadDir.toString(), "isles-no-such-ffmpeg");
    }

    @Test
    void should_detect_browser_native_image_formats() {
        assertThat(MediaService.detectMime(bytes(0xFF, 0xD8, 0xFF, 0xE0))).isEqualTo("image/jpeg");
        assertThat(MediaService.detectMime(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)))
                .isEqualTo("image/png");
        assertThat(MediaService.detectMime("GIF89a".getBytes(StandardCharsets.US_ASCII))).isEqualTo("image/gif");
        assertThat(MediaService.detectMime(bytes('B', 'M', 0x36, 0x00, 0x00, 0x00))).isEqualTo("image/bmp");
        assertThat(MediaService.detectMime(webp())).isEqualTo("image/webp");
        assertThat(MediaService.detectMime(ftyp("avif"))).isEqualTo("image/avif");
        assertThat(MediaService.detectMime(ftyp("avis"))).isEqualTo("image/avif");
        assertThat(MediaService.detectMime(ftyp("heic"))).isEqualTo("image/heic");
        assertThat(MediaService.detectMime(ftyp("mif1", "heic"))).isEqualTo("image/heic");
        assertThat(MediaService.detectMime(ftyp("mif1"))).isEqualTo("image/heif");
    }

    @Test
    void should_detect_video_formats_including_quicktime_and_m4v() {
        assertThat(MediaService.detectMime(ftyp("isom", "mp42"))).isEqualTo("video/mp4");
        assertThat(MediaService.detectMime(ftyp("qt  "))).isEqualTo("video/quicktime");
        assertThat(MediaService.detectMime(ftyp("M4V "))).isEqualTo("video/x-m4v");
        assertThat(MediaService.detectMime(bytes(0x1A, 0x45, 0xDF, 0xA3, 0x93, 0x42, 0x82, 0x88)))
                .isEqualTo("video/webm");
    }

    @Test
    void should_accept_avif_and_degrade_to_original_when_ffmpeg_missing() {
        // 只有 ftyp 头，没有可解码的图像数据：ImageIO 与 ffmpeg 都会失败
        MockMultipartFile file = new MockMultipartFile("file", "a.avif", "image/avif", ftyp("avif"));

        MediaUploadResponse response = mediaService.upload(file, "image", "user-1");

        assertThat(response.mediaType()).isEqualTo("IMAGE");
        assertThat(response.url()).startsWith("/uploads/").endsWith(".avif");
        // 降级：无缩略图、尺寸未知，但上传成功（不是 500）
        assertThat(response.thumbUrl()).isNull();
        assertThat(response.width()).isNull();
        assertThat(response.height()).isNull();
    }

    @Test
    void should_accept_bmp_and_generate_thumbnail() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.bmp", "image/bmp", tinyBmp());

        MediaUploadResponse response = mediaService.upload(file, "image", "user-1");

        assertThat(response.mediaType()).isEqualTo("IMAGE");
        assertThat(response.url()).endsWith(".bmp");
        assertThat(response.width()).isEqualTo(1);
        assertThat(response.height()).isEqualTo(1);
        assertThat(response.thumbUrl()).isNotNull();
    }

    @Test
    void should_reject_mov_declared_as_image() {
        MockMultipartFile file = new MockMultipartFile("file", "a.mov", "video/quicktime", ftyp("qt  "));

        assertThatThrownBy(() -> mediaService.upload(file, "image", "user-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.MEDIA_INVALID_TYPE));
    }

    @Test
    void should_reject_unknown_binary() {
        MockMultipartFile file = new MockMultipartFile("file", "a.bin", "image/png",
                "not-an-image-at-all".getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> mediaService.upload(file, "image", "user-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.MEDIA_INVALID_TYPE));
    }

    // ------------------------------------------------------------------
    // 构造文件头
    // ------------------------------------------------------------------

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    /** ISO BMFF：4 字节 box 长度 + 'ftyp' + major brand + 4 字节版本 + compatible brands */
    private static byte[] ftyp(String majorBrand, String... compatibleBrands) {
        StringBuilder builder = new StringBuilder("0000ftyp").append(majorBrand).append("0000");
        for (String brand : compatibleBrands) {
            builder.append(brand);
        }
        return builder.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] webp() {
        return "RIFF0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);
    }

    /** 1x1 24 位 BMP（BMP 头 + 像素数据）。 */
    private static byte[] tinyBmp() {
        int fileSize = 54 + 4;
        byte[] bmp = new byte[fileSize];
        bmp[0] = 'B';
        bmp[1] = 'M';
        writeInt(bmp, 2, fileSize);
        writeInt(bmp, 10, 54);
        writeInt(bmp, 14, 40);
        writeInt(bmp, 18, 1);
        writeInt(bmp, 22, 1);
        bmp[26] = 1;
        bmp[28] = 24;
        writeInt(bmp, 34, 4);
        return bmp;
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
