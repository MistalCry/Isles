package com.isles.post.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 帖子（表 {@code posts}，docs/06 §3.8）。
 */
@Getter
@Setter
@TableName("posts")
public class Post {

    @TableId(type = IdType.INPUT)
    private String id;
    private String channelId;
    private String boardId;
    private String authorId;
    /**
     * 标题：允许作者清空（blank → null）。
     *
     * <p>{@code updateStrategy = ALWAYS} 必须保留：MyBatis-Plus 的 {@code updateById} 默认跳过 null 字段，
     * 不加这一行「清空标题」会静默不生效（保存成功但库里还是旧值）。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String title;
    private String content;
    private String contentFormat;
    /**
     * 封面：允许作者移除（blank → null），同样需要 ALWAYS 才能把 null 落库。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String coverUrl;
    private Long viewCount;
    private Long likeCount;
    private Long commentCount;
    private Long shareCount;
    private Boolean isPinned;
    private Boolean isEssence;
    /** 是否 AI 代理生成（V13）：前台必须展示 AI 徽章，不可关闭（docs/12 §6）。 */
    private Boolean isAiGenerated;
    private LocalDateTime pinnedAt;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public enum ContentFormat {
        PLAIN, HTML
    }

    public enum Status {
        PUBLISHED, DELETED, HIDDEN
    }
}
