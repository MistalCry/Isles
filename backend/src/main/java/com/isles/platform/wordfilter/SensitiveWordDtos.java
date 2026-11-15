package com.isles.platform.wordfilter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 敏感词接口模型（docs/11 §5.5）。
 */
public final class SensitiveWordDtos {

    private SensitiveWordDtos() {
    }

    public record WordView(
            String id,
            String word,
            String category,
            String action,
            boolean enabled,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record WordPage(List<WordView> items, long total, int page, int size) {
    }

    public record SaveWordRequest(
            @NotBlank(message = "敏感词不能为空")
            @Size(max = 100, message = "敏感词最长 100 个字符")
            String word,

            @Pattern(regexp = "POLITICS|PORN|AD|ABUSE|CUSTOM", message = "分类取值非法")
            String category,

            @Pattern(regexp = "BLOCK|REVIEW|MASK", message = "处置方式取值非法")
            String action,

            Boolean enabled) {
    }

    /**
     * 批量导入：一行一个词，忽略空行与重复；单次最多 500 条。
     */
    public record ImportRequest(
            @NotBlank(message = "导入内容不能为空")
            @Size(max = 20000, message = "单次导入内容过长")
            String words,

            @Pattern(regexp = "POLITICS|PORN|AD|ABUSE|CUSTOM", message = "分类取值非法")
            String category,

            @Pattern(regexp = "BLOCK|REVIEW|MASK", message = "处置方式取值非法")
            String action) {
    }

    public record ImportResult(int created, int skipped) {
    }

    /**
     * 试算：只返回命中的词，不落库（供运营校验词库效果）。
     */
    public record ScanRequest(
            @NotBlank(message = "待检测文本不能为空")
            @Size(max = 20000, message = "待检测文本过长")
            String text) {
    }

    public record ScanHit(String word, String action) {
    }

    public record ScanResult(List<ScanHit> hits) {
    }

    public record HitView(
            String id,
            String word,
            String scene,
            String targetId,
            String userId,
            String action,
            Instant createdAt) {
    }

    public record HitPage(List<HitView> items, long total, int page, int size) {
    }
}
