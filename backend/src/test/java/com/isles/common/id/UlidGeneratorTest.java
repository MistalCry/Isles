package com.isles.common.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ULID 生成器单元测试：长度、字符集、唯一性、时间有序。
 */
class UlidGeneratorTest {

    @Test
    void should_generate_26_characters_with_crockford_base32() {
        String ulid = UlidGenerator.generate();

        assertThat(ulid).hasSize(26).matches("[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(UlidGenerator.isValid(ulid)).isTrue();
    }

    @Test
    void should_generate_unique_ids() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            ids.add(UlidGenerator.generate());
        }
        assertThat(ids).hasSize(1_000);
    }

    @Test
    void should_be_lexicographically_ordered_by_time() throws InterruptedException {
        String first = UlidGenerator.generate();
        Thread.sleep(2L);
        String second = UlidGenerator.generate();

        assertThat(second).isGreaterThan(first);
    }

    @Test
    void should_reject_invalid_ulid() {
        assertThat(UlidGenerator.isValid(null)).isFalse();
        assertThat(UlidGenerator.isValid("too-short")).isFalse();
        assertThat(UlidGenerator.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAI")).isFalse(); // 含非法字符 I
        assertThat(UlidGenerator.isValid("01ARZ3NDEKTSV4RRFFQ69G5FAV")).isTrue();
    }
}
