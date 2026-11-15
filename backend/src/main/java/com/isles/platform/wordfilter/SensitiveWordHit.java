package com.isles.platform.wordfilter;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 敏感词命中记录（表 {@code sensitive_word_hits}，docs/11 §4.1）：只追加。
 *
 * <p>{@code word} 是快照，词条被删除后仍可追溯；{@code targetId} 为帖子/评论 ID。
 */
@Getter
@Setter
@TableName("sensitive_word_hits")
public class SensitiveWordHit {

    @TableId(type = IdType.INPUT)
    private String id;
    private String wordId;
    private String word;
    private String scene;
    private String targetId;
    private String userId;
    private String action;
    private LocalDateTime createdAt;
}
