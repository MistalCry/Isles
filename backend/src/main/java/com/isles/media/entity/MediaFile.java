package com.isles.media.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 上传媒体文件（表 {@code media_files}，docs/06 §3.12）：M1 存本地磁盘，预留对象存储适配。
 */
@Getter
@Setter
@TableName("media_files")
public class MediaFile {

    @TableId(type = IdType.INPUT)
    private String id;
    private String uploaderId;
    private String mediaType;
    /** 相对于上传根目录的路径，如 {@code 2026/11/{随机文件名}.jpg}。 */
    private String storagePath;
    private String url;
    private String thumbUrl;
    private Long sizeBytes;
    private Integer width;
    private Integer height;
    private Long durationMs;
    private String status;
    private LocalDateTime createdAt;

    public enum MediaType {
        IMAGE, VIDEO
    }

    public enum Status {
        ACTIVE, DELETED
    }
}
