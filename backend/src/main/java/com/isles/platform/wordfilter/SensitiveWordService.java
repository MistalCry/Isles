package com.isles.platform.wordfilter;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.time.ClockTimes;
import com.isles.platform.audit.PlatformAuditActions;
import com.isles.platform.audit.PlatformAuditService;
import com.isles.platform.setting.PlatformSettingService;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感词服务（docs/11 §5.5）：词库管理 + 内容命中记录。
 *
 * <p>匹配策略：词库启用项全量缓存（60 秒），逐词包含匹配，忽略大小写。
 *
 * <p>处理方式由配置项 {@code content.sensitiveWord.mode} 决定（docs/11 §5.5）：
 * <ul>
 *   <li>{@code REVIEW}（默认）：只记录命中，内容照常发布，避免误杀；</li>
 *   <li>{@code MASK}：把命中词替换为 {@code ***} 后入库（富文本只替换文本节点，不动标签属性）；</li>
 *   <li>{@code BLOCK}：直接拒绝发布（422 {@code SENSITIVE_WORD_BLOCKED}），返回命中提示。</li>
 * </ul>
 * 命中记录写入失败不影响内容发布（记录 WARN 后继续）；BLOCK 的拒绝不受此保护。
 */
@Service
@RequiredArgsConstructor
public class SensitiveWordService {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordService.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_IMPORT_WORDS = 500;
    private static final String SETTING_ENABLED = "content.sensitiveWord.enabled";
    private static final String SETTING_MODE = "content.sensitiveWord.mode";

    /** 处理方式（与配置项白名单一致）。 */
    public static final String MODE_REVIEW = "REVIEW";
    public static final String MODE_MASK = "MASK";
    public static final String MODE_BLOCK = "BLOCK";

    private static final String MASK_REPLACEMENT = "***";
    /** 拒绝提示里最多列出的命中词数量，避免把整个词库回显给用户。 */
    private static final int MAX_WORDS_IN_MESSAGE = 3;

    private final SensitiveWordMapper wordMapper;
    private final SensitiveWordHitMapper hitMapper;
    private final PlatformSettingService settingService;
    private final PlatformAuditService auditService;

    private volatile CachedWords cache;

    private record CachedWords(List<SensitiveWord> words, long loadedAt) {
    }

    private record Match(String wordId, String word, String action) {
    }

    /** 一次命中的词（对外暴露给内容服务，用于落库记录）。 */
    public record Hit(String wordId, String word) {
    }

    /**
     * 检查结果。
     *
     * @param text 应当入库的正文（MASK 模式已打码；其余模式与入参一致）
     * @param hits 命中的词（为空表示未命中或扫描关闭）
     * @param mode 本次使用的处理方式
     */
    public record Inspection(String text, List<Hit> hits, String mode) {

        public boolean hasHits() {
            return !hits.isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // 词库管理
    // ------------------------------------------------------------------

    public SensitiveWordDtos.WordPage list(String keyword, Integer page, Integer size) {
        int pageNo = page == null || page <= 0 ? 1 : page;
        int pageSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        String pattern = likePattern(keyword);
        long total = wordMapper.countPage(pattern);
        List<SensitiveWordDtos.WordView> items = wordMapper
                .selectPage(pattern, pageSize, (pageNo - 1) * pageSize).stream()
                .map(this::toView)
                .toList();
        return new SensitiveWordDtos.WordPage(items, total, pageNo, pageSize);
    }

    @Transactional
    public SensitiveWordDtos.WordView create(SensitiveWordDtos.SaveWordRequest request, String operatorId) {
        String word = request.word().trim();
        if (wordMapper.countByWord(word) > 0) {
            throw new AppException(ErrorCode.SENSITIVE_WORD_DUPLICATE);
        }
        SensitiveWord entity = new SensitiveWord();
        entity.setId(UlidGenerator.generate());
        entity.setWord(word);
        entity.setCategory(request.category() == null ? SensitiveWord.Category.CUSTOM.name() : request.category());
        entity.setAction(request.action() == null ? SensitiveWord.Action.REVIEW.name() : request.action());
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setCreatedBy(operatorId);
        entity.setCreatedAt(ClockTimes.nowUtc());
        entity.setUpdatedAt(ClockTimes.nowUtc());
        wordMapper.insert(entity);
        invalidate();
        auditService.record(operatorId, null, PlatformAuditActions.WORD_CREATE, "WORD", entity.getId(),
                Map.of("word", word, "action", entity.getAction()));
        return toView(entity);
    }

    @Transactional
    public SensitiveWordDtos.WordView update(String id, SensitiveWordDtos.SaveWordRequest request,
                                             String operatorId) {
        SensitiveWord entity = require(id);
        String word = request.word().trim();
        if (!word.equals(entity.getWord()) && wordMapper.countByWord(word) > 0) {
            throw new AppException(ErrorCode.SENSITIVE_WORD_DUPLICATE);
        }
        entity.setWord(word);
        entity.setCategory(request.category() == null ? entity.getCategory() : request.category());
        entity.setAction(request.action() == null ? entity.getAction() : request.action());
        entity.setEnabled(request.enabled() == null ? Boolean.TRUE.equals(entity.getEnabled()) : request.enabled());
        entity.setUpdatedAt(ClockTimes.nowUtc());
        wordMapper.updateById(entity);
        invalidate();
        auditService.record(operatorId, null, PlatformAuditActions.WORD_UPDATE, "WORD", id,
                Map.of("word", word, "action", entity.getAction(), "enabled", entity.getEnabled()));
        return toView(entity);
    }

    @Transactional
    public void delete(String id, String operatorId) {
        SensitiveWord entity = require(id);
        wordMapper.deleteById(id);
        invalidate();
        auditService.record(operatorId, null, PlatformAuditActions.WORD_DELETE, "WORD", id,
                Map.of("word", entity.getWord()));
    }

    /**
     * 批量导入：一行一个词，忽略空行与已存在的词。
     */
    @Transactional
    public SensitiveWordDtos.ImportResult importWords(SensitiveWordDtos.ImportRequest request, String operatorId) {
        Set<String> words = new LinkedHashSet<>();
        for (String line : request.words().split("\\R")) {
            String value = line.trim();
            if (!value.isEmpty()) {
                words.add(value);
            }
        }
        if (words.size() > MAX_IMPORT_WORDS) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "单次最多导入 " + MAX_IMPORT_WORDS + " 个词");
        }
        String category = request.category() == null ? SensitiveWord.Category.CUSTOM.name() : request.category();
        String action = request.action() == null ? SensitiveWord.Action.REVIEW.name() : request.action();

        int created = 0;
        int skipped = 0;
        for (String word : words) {
            if (wordMapper.countByWord(word) > 0) {
                skipped++;
                continue;
            }
            SensitiveWord entity = new SensitiveWord();
            entity.setId(UlidGenerator.generate());
            entity.setWord(word);
            entity.setCategory(category);
            entity.setAction(action);
            entity.setEnabled(true);
            entity.setCreatedBy(operatorId);
            entity.setCreatedAt(ClockTimes.nowUtc());
            entity.setUpdatedAt(ClockTimes.nowUtc());
            wordMapper.insert(entity);
            created++;
        }
        invalidate();
        auditService.record(operatorId, null, PlatformAuditActions.WORD_IMPORT, "WORD", null,
                Map.of("created", created, "skipped", skipped));
        log.info("敏感词：批量导入 created={} skipped={}", created, skipped);
        return new SensitiveWordDtos.ImportResult(created, skipped);
    }

    // ------------------------------------------------------------------
    // 命中记录与扫描
    // ------------------------------------------------------------------

    public SensitiveWordDtos.HitPage hits(String scene, String word, Integer page, Integer size) {
        int pageNo = page == null || page <= 0 ? 1 : page;
        int pageSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        String sceneFilter = blankToNull(scene);
        String wordFilter = blankToNull(word);
        long total = hitMapper.countPage(sceneFilter, wordFilter);
        List<SensitiveWordDtos.HitView> items = hitMapper
                .selectPage(sceneFilter, wordFilter, pageSize, (pageNo - 1) * pageSize).stream()
                .map(hit -> new SensitiveWordDtos.HitView(hit.getId(), hit.getWord(), hit.getScene(),
                        hit.getTargetId(), hit.getUserId(), hit.getAction(),
                        ClockTimes.toInstant(hit.getCreatedAt())))
                .toList();
        return new SensitiveWordDtos.HitPage(items, total, pageNo, pageSize);
    }

    /**
     * 试算：只返回命中词，不写库（运营校验词库效果）。
     */
    public SensitiveWordDtos.ScanResult preview(String text) {
        List<SensitiveWordDtos.ScanHit> hits = match(text).stream()
                .map(match -> new SensitiveWordDtos.ScanHit(match.word(), match.action()))
                .toList();
        return new SensitiveWordDtos.ScanResult(hits);
    }

    /**
     * 内容发布前的检查：按当前模式返回「要入库的文本」与命中词。
     *
     * <p>BLOCK 模式直接抛 {@link ErrorCode#SENSITIVE_WORD_BLOCKED}；其余异常一律降级为「原文放行」，
     * 保证词库/配置异常不会阻断用户发帖。
     *
     * @param text 正文（富文本传净化后的 HTML）
     * @param html 是否按 HTML 处理（匹配取纯文本，MASK 只替换文本节点）
     */
    public Inspection inspect(String text, boolean html) {
        try {
            if (!settingService.getBoolean(SETTING_ENABLED)) {
                return new Inspection(text, List.of(), MODE_REVIEW);
            }
            String mode = currentMode();
            String plain = html ? Jsoup.parse(text == null ? "" : text).text() : text;
            List<Match> matches = match(plain);
            if (matches.isEmpty()) {
                return new Inspection(text, List.of(), mode);
            }
            if (MODE_BLOCK.equals(mode)) {
                throw blocked(matches);
            }
            String result = MODE_MASK.equals(mode) ? mask(text, html, matches) : text;
            return new Inspection(result, toHits(matches), mode);
        } catch (AppException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("敏感词检查失败，按原文放行", ex);
            return new Inspection(text, List.of(), MODE_REVIEW);
        }
    }

    /**
     * 内容落库后记录命中（{@code action} 记录本次实际处理方式，便于运营核对）。
     */
    public void recordHits(String scene, String targetId, String userId, Inspection inspection) {
        if (inspection == null || inspection.hits().isEmpty()) {
            return;
        }
        try {
            for (Hit hit : inspection.hits()) {
                SensitiveWordHit entity = new SensitiveWordHit();
                entity.setId(UlidGenerator.generate());
                entity.setWordId(hit.wordId());
                entity.setWord(hit.word());
                entity.setScene(scene);
                entity.setTargetId(targetId);
                entity.setUserId(userId);
                entity.setAction(inspection.mode());
                entity.setCreatedAt(ClockTimes.nowUtc());
                hitMapper.insert(entity);
            }
            log.info("敏感词命中 scene={} targetId={} count={} mode={}", scene, targetId,
                    inspection.hits().size(), inspection.mode());
        } catch (RuntimeException ex) {
            log.warn("敏感词命中记录失败 scene={} targetId={}", scene, targetId, ex);
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private List<Match> match(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<Match> matches = new ArrayList<>();
        for (SensitiveWord word : enabledWords()) {
            if (word.getWord() == null || word.getWord().isBlank()) {
                continue;
            }
            if (lower.contains(word.getWord().toLowerCase(Locale.ROOT))) {
                matches.add(new Match(word.getId(), word.getWord(), word.getAction()));
            }
        }
        return matches;
    }

    private String currentMode() {
        String mode = settingService.getString(SETTING_MODE);
        if (MODE_MASK.equals(mode) || MODE_BLOCK.equals(mode)) {
            return mode;
        }
        return MODE_REVIEW;
    }

    /**
     * 打码：富文本只替换文本节点，避免误改 {@code href}/{@code class} 等属性。
     */
    private String mask(String text, boolean html, List<Match> matches) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (!html) {
            return maskPlain(text, matches);
        }
        org.jsoup.nodes.Document document = Jsoup.parse(text);
        org.jsoup.nodes.Element body = document.body();
        for (org.jsoup.nodes.Element element : body.getAllElements()) {
            for (org.jsoup.nodes.Node child : new ArrayList<>(element.childNodes())) {
                if (child instanceof org.jsoup.nodes.TextNode textNode) {
                    String original = textNode.getWholeText();
                    String masked = maskPlain(original, matches);
                    if (!masked.equals(original)) {
                        textNode.text(masked);
                    }
                }
            }
        }
        return body.html();
    }

    private String maskPlain(String text, List<Match> matches) {
        String result = text;
        for (Match match : matches) {
            result = Pattern.compile(Pattern.quote(match.word()), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(result)
                    .replaceAll(Matcher.quoteReplacement(MASK_REPLACEMENT));
        }
        return result;
    }

    private AppException blocked(List<Match> matches) {
        List<String> words = matches.stream().map(Match::word).limit(MAX_WORDS_IN_MESSAGE).toList();
        return new AppException(ErrorCode.SENSITIVE_WORD_BLOCKED,
                "内容包含敏感词：" + String.join("、", words) + "，请修改后重试");
    }

    private List<Hit> toHits(List<Match> matches) {
        List<Hit> hits = new ArrayList<>(matches.size());
        for (Match match : matches) {
            hits.add(new Hit(match.wordId(), match.word()));
        }
        return List.copyOf(hits);
    }

    private List<SensitiveWord> enabledWords() {
        CachedWords current = cache;
        if (current != null && System.currentTimeMillis() - current.loadedAt() < CACHE_TTL.toMillis()) {
            return current.words();
        }
        synchronized (this) {
            current = cache;
            if (current != null && System.currentTimeMillis() - current.loadedAt() < CACHE_TTL.toMillis()) {
                return current.words();
            }
            List<SensitiveWord> words = List.copyOf(wordMapper.selectEnabled());
            cache = new CachedWords(words, System.currentTimeMillis());
            return words;
        }
    }

    private SensitiveWord require(String id) {
        SensitiveWord entity = wordMapper.selectById(id);
        if (entity == null) {
            throw new AppException(ErrorCode.SENSITIVE_WORD_NOT_FOUND);
        }
        return entity;
    }

    private void invalidate() {
        cache = null;
    }

    private SensitiveWordDtos.WordView toView(SensitiveWord word) {
        return new SensitiveWordDtos.WordView(word.getId(), word.getWord(), word.getCategory(), word.getAction(),
                Boolean.TRUE.equals(word.getEnabled()), word.getCreatedBy(),
                ClockTimes.toInstant(word.getCreatedAt()), ClockTimes.toInstant(word.getUpdatedAt()));
    }

    private String likePattern(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String value = keyword.trim().replaceAll("[%_\\\\]", "");
        return value.isEmpty() ? null : "%" + value + "%";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
