package com.isles.platform.wordfilter;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 敏感词（表 {@code sensitive_words}，docs/11 §4.1）。
 *
 * <p>{@code action} 语义：{@code REVIEW} 记录不阻断（V7 默认）、{@code MASK} 打码、{@code BLOCK} 拒绝发布。
 * 当前版本只执行 REVIEW，其余取值作为配置预留，避免误杀。
 */
@Getter
@Setter
@TableName("sensitive_words")
public class SensitiveWord {

    @TableId(type = IdType.INPUT)
    private String id;
    private String word;
    private String category;
    private String action;
    private Boolean enabled;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum Action {
        BLOCK, REVIEW, MASK
    }

    public enum Category {
        POLITICS, PORN, AD, ABUSE, CUSTOM
    }
}
