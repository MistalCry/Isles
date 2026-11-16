package com.isles.agent.service;

import com.isles.agent.entity.AgentMemory;
import com.isles.agent.mapper.AgentMemoryMapper;
import com.isles.common.id.UlidGenerator;
import com.isles.common.time.ClockTimes;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AI 代理记忆读写（V13 / docs/12 §3.3、§5.3）。
 *
 * <p>核心是**去重**：发帖前把候选标题归一化成指纹，与最近 7 天自身 TOPIC 记忆比对——
 * 指纹相同或相似度超过阈值即判定为「重复话题」，由 {@code AgentGuard} 拦下。
 *
 * <p>相似度用「字符二元组 Dice 系数」（{@code 2|A∩B| / (|A|+|B|)}）：对中文短标题比 Jaccard 更稳
 * （Jaccard 对「一词之差」惩罚过重，两句话差一个字就掉到 0.67）。阈值 {@value #SIMILARITY_THRESHOLD}，
 * 宁可多拦一次也不让同一代理反复讲同一件事。
 */
@Service
@RequiredArgsConstructor
public class AgentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryService.class);

    /** 去重窗口：7 天（docs/12 §5.3）。 */
    public static final int DEDUPE_DAYS = 7;
    /** 相似度阈值：超过即视为同一话题。 */
    public static final double SIMILARITY_THRESHOLD = 0.75;
    /** 参与比较的历史话题条数上限。 */
    private static final int COMPARE_LIMIT = 50;
    /** 记忆正文上限（与列宽一致）。 */
    private static final int MAX_CONTENT = 500;

    private final AgentMemoryMapper memoryMapper;

    /**
     * 写入一条记忆（同指纹在窗口内已存在则跳过，避免重复堆积）。
     *
     * @return 实际写入的 id；未写入返回 null
     */
    public String remember(String agentId, String kind, String content, String sourceRef) {
        if (agentId == null || content == null || content.isBlank()) {
            return null;
        }
        String text = truncate(content.trim(), MAX_CONTENT);
        String fingerprint = fingerprint(text);
        LocalDateTime since = ClockTimes.nowUtc().minusDays(DEDUPE_DAYS);
        if (memoryMapper.countByFingerprint(agentId, fingerprint, since) > 0) {
            return null;
        }
        AgentMemory memory = new AgentMemory();
        memory.setId(UlidGenerator.generate());
        memory.setAgentId(agentId);
        memory.setKind(normalizeKind(kind));
        memory.setContent(text);
        memory.setFingerprint(fingerprint);
        memory.setSourceRef(sourceRef == null ? null : truncate(sourceRef, 64));
        memory.setCreatedAt(ClockTimes.nowUtc());
        memoryMapper.insert(memory);
        return memory.getId();
    }

    /**
     * 召回记忆（工具 {@code recall}）：按关键词模糊匹配，无关键词时返回最近若干条。
     */
    public List<AgentMemory> recall(String agentId, String query, int limit) {
        int capped = Math.min(Math.max(limit, 1), 20);
        List<String> patterns = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            for (String token : query.trim().split("\\s+")) {
                if (!token.isBlank()) {
                    patterns.add("%" + escapeLike(token.trim()) + "%");
                }
            }
        }
        return memoryMapper.search(agentId, patterns.isEmpty() ? null : patterns, capped);
    }

    /**
     * 最近话题记忆（提示词里用来「别重复自己」）。
     */
    public List<AgentMemory> recentTopics(String agentId, int limit) {
        return memoryMapper.selectRecent(agentId, AgentMemory.Kind.TOPIC.name(),
                ClockTimes.nowUtc().minusDays(DEDUPE_DAYS), Math.min(Math.max(limit, 1), COMPARE_LIMIT));
    }

    /**
     * 候选文本是否与最近话题重复（指纹相同，或相似度 ≥ {@link #SIMILARITY_THRESHOLD}）。
     */
    public boolean isDuplicateTopic(String agentId, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String fingerprint = fingerprint(text);
        LocalDateTime since = ClockTimes.nowUtc().minusDays(DEDUPE_DAYS);
        if (memoryMapper.countByFingerprint(agentId, fingerprint, since) > 0) {
            return true;
        }
        Set<String> candidate = bigrams(normalize(text));
        if (candidate.isEmpty()) {
            return false;
        }
        for (AgentMemory memory : recentTopics(agentId, COMPARE_LIMIT)) {
            double similarity = dice(candidate, bigrams(normalize(memory.getContent())));
            if (similarity >= SIMILARITY_THRESHOLD) {
                log.debug("话题重复：agentId={} similarity={} existing={}", agentId, similarity,
                        memory.getContent());
                return true;
            }
        }
        return false;
    }

    /**
     * 归一化指纹：小写、去掉标点与空白，取 MD5（32 位十六进制，与列宽 CHAR(32) 一致）。
     */
    public static String fingerprint(String text) {
        String normalized = normalize(text);
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // MD5 是 JDK 必备算法，理论上不可达；退化为归一化文本的 hashCode，保证功能不中断
            return String.format("%032x", (long) normalized.hashCode());
        }
    }

    /** 只保留字母、数字与中日韩字符，压缩空白。 */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                builder.append(Character.toLowerCase(c));
            }
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private static Set<String> bigrams(String normalized) {
        Set<String> grams = new LinkedHashSet<>();
        if (normalized.length() < 2) {
            if (!normalized.isEmpty()) {
                grams.add(normalized);
            }
            return grams;
        }
        for (int i = 0; i + 1 < normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }

    private static double dice(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        int intersection = 0;
        for (String gram : left) {
            if (right.contains(gram)) {
                intersection++;
            }
        }
        return (2.0 * intersection) / (left.size() + right.size());
    }

    private String normalizeKind(String kind) {
        if (kind == null) {
            return AgentMemory.Kind.FACT.name();
        }
        for (AgentMemory.Kind value : AgentMemory.Kind.values()) {
            if (value.name().equalsIgnoreCase(kind.trim())) {
                return value.name();
            }
        }
        return AgentMemory.Kind.FACT.name();
    }

    private String escapeLike(String token) {
        return token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
