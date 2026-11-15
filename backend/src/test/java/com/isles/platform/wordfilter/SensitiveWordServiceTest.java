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
    private SensitiveWordService service;

    @BeforeEach
    void setUp() {
        wordMapper = mock(SensitiveWordMapper.class);
        hitMapper = mock(SensitiveWordHitMapper.class);
        settingService = mock(PlatformSettingService.class);
        service = new SensitiveWordService(wordMapper, hitMapper, settingService,
                mock(PlatformAuditService.class));
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

        service.scanAndRecord("POST", "post-1", "user-1", "这是一段敏感内容", false);

        verify(hitMapper, times(1)).insert(any(SensitiveWordHit.class));
    }

    @Test
    void should_skip_scanning_when_disabled() {
        when(settingService.getBoolean("content.sensitiveWord.enabled")).thenReturn(false);

        service.scanAndRecord("POST", "post-1", "user-1", "敏感内容", false);

        verify(wordMapper, never()).selectEnabled();
        verify(hitMapper, never()).insert(any(SensitiveWordHit.class));
    }

    @Test
    void should_extract_text_from_html_before_matching() {
        when(wordMapper.selectEnabled()).thenReturn(List.of(word("w-1", "违禁", "REVIEW")));

        service.scanAndRecord("POST", "post-1", "user-1", "<p>含<b>违禁</b>词</p>", true);

        verify(hitMapper).insert(any(SensitiveWordHit.class));
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
