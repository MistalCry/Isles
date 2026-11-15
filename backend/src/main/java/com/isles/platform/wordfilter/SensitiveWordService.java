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

/**
 * 敏感词服务（docs/11 §5.5）：词库管理 + 内容命中记录。
 *
 * <p>匹配策略：词库启用项全量缓存（60 秒），逐词包含匹配，忽略大小写；命中只记录不阻断（REVIEW），
 * 避免误杀。命中记录写入失败不影响内容发布（记录 WARN 后继续）。
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

    private final SensitiveWordMapper wordMapper;
    private final SensitiveWordHitMapper hitMapper;
    private final PlatformSettingService settingService;
    private final PlatformAuditService auditService;

    private volatile CachedWords cache;

    private record CachedWords(List<SensitiveWord> words, long loadedAt) {
    }

    private record Match(String wordId, String word, String action) {
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
     * 内容发布后的扫描与记录（REVIEW 模式）：任何异常都不影响发布流程。
     *
     * @param scene    POST / COMMENT
     * @param targetId 帖子或评论 ID
     * @param text     正文；{@code html=true} 时先抽取纯文本
     */
    public void scanAndRecord(String scene, String targetId, String userId, String text, boolean html) {
        try {
            if (!settingService.getBoolean(SETTING_ENABLED)) {
                return;
            }
            String plain = html ? Jsoup.parse(text == null ? "" : text).text() : text;
            List<Match> matches = match(plain);
            if (matches.isEmpty()) {
                return;
            }
            for (Match match : matches) {
                SensitiveWordHit hit = new SensitiveWordHit();
                hit.setId(UlidGenerator.generate());
                hit.setWordId(match.wordId());
                hit.setWord(match.word());
                hit.setScene(scene);
                hit.setTargetId(targetId);
                hit.setUserId(userId);
                hit.setAction(match.action());
                hit.setCreatedAt(ClockTimes.nowUtc());
                hitMapper.insert(hit);
            }
            log.info("敏感词命中 scene={} targetId={} count={}", scene, targetId, matches.size());
        } catch (RuntimeException ex) {
            log.warn("敏感词扫描失败 scene={} targetId={}", scene, targetId, ex);
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
