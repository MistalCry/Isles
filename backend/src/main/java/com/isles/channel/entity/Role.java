package com.isles.channel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 频道身份组（表 {@code roles}，docs/06 §3.4）：权限点以 JSON 数组字符串存储。
 */
@Getter
@Setter
@TableName("roles")
public class Role {

    @TableId(type = IdType.INPUT)
    private String id;
    private String channelId;
    private String name;
    private String description;
    /** JSON 数组，如 {@code ["post.create","comment.create"]}。 */
    private String permissions;
    private String scopeBoardId;
    private Boolean isSystem;
    private Integer sort;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
