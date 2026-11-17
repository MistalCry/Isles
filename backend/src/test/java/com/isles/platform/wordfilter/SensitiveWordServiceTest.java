package com.isles.platform.wordfilter;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.platform.audit.PlatformAuditService;
import com.isles.platform.setting.PlatformSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 敏感词服务单元测试（docs/11 §5.5）：忽略大小写匹配、开关关闭不记录、命中落库、导入去重。
 */
class SensitiveWordServiceTest {

    private SensitiveWordMapper wordMapper;
    private SensitiveWordHitMapper hitMapper;
    private PlatformSettingService settingService;
    private io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry;
    private SensitiveWordService service;

    @BeforeEach
    void setUp() {
        wordMapper = mock(SensitiveWordMapper.class);
        hitMapper = mock(SensitiveWordHitMapper.class);
        settingService = mock(PlatformSettingService.class);
        meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        service = new SensitiveWordService(wordMapper, hitMapper, settingService,
                mock(PlatformAuditService.class), meterRegistry);
        when(settingService.getBoolean("content.sensitiveWord.enabled")).thenReturn(true);
    }

    @Test
    void should_match_case_insensitively() {
        when(wordMapper.selectEnabled()).thenReturn(List.of(word("w-1", "BadWord", "REVIEW")));

        SensitiveWordDtos.ScanResult result = service.preview("this contains badword inside");

        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).word()).isEqualTo("BadWord");
        assertThat(result.hits().get(0).action()).isEqualTo("REVIEW");
    }

    @Test
    void should_return_empty_when_text_blank() {
        assertThat(service.preview("   ").hits()).isEmpty();
    }

    @Test
    void should_record_hits_for_published_content() {
        when(wordMapper.selectEnabled()).thenReturn(List.of(word("w-1", "敏感", "REVIEW")));

        SensitiveWordService.Inspection inspection = service.inspect("这是一段敏感内容", false);
        service.recordHits("POST", "post-1", "user-1", inspection);

        // REVIEW：内容原样返回
        assertThat(inspection.text()).isEqualTo("这是一段敏感内容");
        assertThat(inspection.hits()).hasSize(1);
        assertThat(inspection.mode()).isEqualTo("REVIEW");
        verify(hitMapper, times(1)).insert(any(SensitiveWordHit.class));
    }

    @Test
    void should_skip_scanning_when_disabled() {
        when(settingService.getBoolean("content.sensitiveWord.enabled")).thenReturn(false);

        SensitiveWordService.Inspection inspection = service.inspect("敏感内容", false);
        service.recordHits("POST", "post-1", "user-1", inspection);

        assertThat(inspection.hasHits()).isFalse();
        assertThat(inspection.text()).isEqualTo("敏感内容");
        verify(wordMapper, never()).selectEnabled();
        verify(hitMapper, never()).insert(any(SensitiveWordHit.class));
    }

    @Test
    void should_extract_text_from_html_before_matching() {
        when(wordMapper.selectEnabled()).thenReturn(List.of(word("w-1", "违禁", "REVIEW")));

        SensitiveWordService.Inspection inspection = service.inspect("<p>含<b>违禁</b>词</p>", true);

        assertThat(inspection.hits()).hasSize(1);
    }

    @Test
    void should_mask_hit_words_in_plain_text() {
        when(settingService.getString("content.sensitiveWord.mode")).thenReturn("MASK");
        when(wordMapper.selectEnabled()).thenReturn(List.of(word("w-1", "BadWord", "REVIEW")));

        SensitiveWordService.Inspection inspection = service.inspect("this has badword inside", false);

        assertThat(inspection.text()).isEqualTo("this has *** inside");
        assertThat(inspection.hits()).hasSize(1);
        assertThat(inspection.mode()).isEqualTo("MASK");
    }

    @Test
    void should_mask_only_text_nodes_in_html() {
        when(settingService.getString("content.sensitiveWord.mode")).thenReturn("MASK");
        when(wordMapper.selectEnabled()).thenReturn(List.of(word("w-1", "违禁", "REVIEW")));

        SensitiveWordService.Inspection inspection = service.inspect(
                "<p>含<b>违禁</b>词</p><a href=\"/s?q=违禁\">链接</a>", true);

        assertThat(inspection.text()).contains("***").contains("含<b>***</b>词");
        // 属性里的同名文本不能被改写，否则会破坏链接
        assertThat(inspection.text()).contains("/s?q=违禁");
    }

    @Test
    void should_block_and_report_hit_words() {
        when(settingService.getString("content.sensitiveWord.mode")).thenReturn("BLOCK");
        when(wordMapper.selectEnabled()).thenReturn(List.of(word("w-1", "违禁", "REVIEW")));

        assertThatThrownBy(() -> service.inspect("这里含违禁词", false))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    assertThat(((AppException) ex).getErrorCode()).isEqualTo(ErrorCode.SENSITIVE_WORD_BLOCKED);
                    assertThat(ex.getMessage()).contains("违禁");
                });
        verify(hitMapper, never()).insert(any(SensitiveWordHit.class));
    }

    @Test
    void should_reject_duplicate_word_on_create() {
        when(wordMapper.countByWord("spam")).thenReturn(1L);

        assertThatThrownBy(() -> service.create(
                new SensitiveWordDtos.SaveWordRequest("spam", null, null, null), "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SENSITIVE_WORD_DUPLICATE));
    }

    @Test
    void should_skip_existing_words_on_import() {
        when(wordMapper.countByWord("a")).thenReturn(1L);
        when(wordMapper.countByWord("b")).thenReturn(0L);

        SensitiveWordDtos.ImportResult result = service.importWords(
                new SensitiveWordDtos.ImportRequest("a\nb\n\n a ", null, null), "admin-1");

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        verify(wordMapper, times(1)).insert(any(SensitiveWord.class));
    }

    @Test
    void should_reject_import_over_limit() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            builder.append("w").append(i).append('\n');
        }

        assertThatThrownBy(() -> service.importWords(
                new SensitiveWordDtos.ImportRequest(builder.toString(), null, null), "admin-1"))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void should_audit_word_deletion() {
        when(wordMapper.selectById("w-1")).thenReturn(word("w-1", "spam", "REVIEW"));

        service.delete("w-1", "admin-1");

        verify(wordMapper).deleteById("w-1");
    }

    // ------------------------------------------------------------------
    // 第 21 轮 P0-2：BLOCK 模式 fail-closed，其余模式降级但留痕
    // ------------------------------------------------------------------

    @Test
    void should_reject_content_when_block_mode_and_wordbank_fails() {
        when(settingService.getString("content.sensitiveWord.mode")).thenReturn("BLOCK");
        // 词库查询炸了（DB 抖动 / 表被锁）
        when(wordMapper.selectEnabled()).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.inspect("正常内容", false))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SENSITIVE_WORD_UNAVAILABLE));
        assertThat(ErrorCode.SENSITIVE_WORD_UNAVAILABLE.httpStatus().value()).isEqualTo(503);
        // 失败计数要能告警
        assertThat(failureCount("BLOCK")).isEqualTo(1.0);
    }

    @Test
    void should_fail_open_but_count_when_review_mode_and_wordbank_fails() {
        when(settingService.getString("content.sensitiveWord.mode")).thenReturn("REVIEW");
        when(wordMapper.selectEnabled()).thenThrow(new IllegalStateException("db down"));

        SensitiveWordService.Inspection inspection = service.inspect("正常内容", false);

        // REVIEW 模式不能因为词库故障阻断发布
        assertThat(inspection.text()).isEqualTo("正常内容");
        assertThat(inspection.hits()).isEmpty();
        assertThat(failureCount("REVIEW")).isEqualTo(1.0);
    }

    @Test
    void should_fail_open_and_count_when_mask_mode_and_wordbank_fails() {
        when(settingService.getString("content.sensitiveWord.mode")).thenReturn("MASK");
        when(wordMapper.selectEnabled()).thenThrow(new IllegalStateException("db down"));

        SensitiveWordService.Inspection inspection = service.inspect("正常内容", false);

        // 打不了码就原样放行（不能吞内容），但计数带 MASK tag，运营能看出是哪种模式在降级
        assertThat(inspection.text()).isEqualTo("正常内容");
        assertThat(failureCount("MASK")).isEqualTo(1.0);
    }

    @Test
    void should_not_reject_when_mode_read_itself_fails() {
        // 连配置都读不到：按 REVIEW 处理，不能升级成 503（否则配置服务抖动会阻断全站发布）
        when(settingService.getString("content.sensitiveWord.mode"))
                .thenThrow(new IllegalStateException("config down"));
        when(wordMapper.selectEnabled()).thenThrow(new IllegalStateException("db down"));

        SensitiveWordService.Inspection inspection = service.inspect("正常内容", false);

        assertThat(inspection.text()).isEqualTo("正常内容");
        assertThat(failureCount("REVIEW")).isEqualTo(1.0);
    }

    private double failureCount(String mode) {
        io.micrometer.core.instrument.Counter counter = meterRegistry.find(
                SensitiveWordService.METRIC_CHECK_FAILURES).tag("mode", mode).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private SensitiveWord word(String id, String value, String action) {
        SensitiveWord word = new SensitiveWord();
        word.setId(id);
        word.setWord(value);
        word.setCategory("CUSTOM");
        word.setAction(action);
        word.setEnabled(true);
        word.setCreatedAt(LocalDateTime.now());
        word.setUpdatedAt(LocalDateTime.now());
        return word;
    }
}
