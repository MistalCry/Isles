package com.isles.channel.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 频道（表 {@code channels}，docs/06 §3.3）。
 */
@Getter
@Setter
@TableName("channels")
public class Channel {

    @TableId(type = IdType.INPUT)
    private String id;
    private Long channelNo;
    private String name;
    private String avatarUrl;
    private String coverUrl;
    private String description;
    private String type;
    private String visibility;
    private String ownerId;
    private Long memberCount;
    private Long postCount;
    private String status;
    /** 全员禁言到期时间；NULL 表示未开启（V2 新增）。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime muteAllUntil;
    /** 频道扩展设置 JSON（V2 新增，预留）。 */
    private String settings;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    /**
     * 非持久化字段：查询"我的频道"时联表带出当前用户身份组名称，避免 N+1。
     */
    @TableField(exist = false)
    private String roleName;

    /** 公开（自由加入）/ 私密（仅邀请加入）。 */
    public enum Type {
        PUBLIC, PRIVATE
    }

    /** 公开可见 / 仅成员可见（私密频道可设为公开可见）。 */
    public enum Visibility {
        PUBLIC_VISIBLE, MEMBERS_ONLY
    }

    public enum Status {
        ACTIVE, DISSOLVED, BANNED
    }
}
