package com.isles.social.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 好友关系（表 {@code friend_relations}，V4__social_realtime.sql）。
 *
 * <p>采用**有向行**模型（{@code user_id} → {@code target_user_id}）：
 * <ul>
 *   <li>好友 = 两条 {@code ACCEPTED} 行（A→B 与 B→A），保证双向语义与各自独立的删除/拉黑；</li>
 *   <li>申请 = 一条 {@code PENDING} 行；拒绝 = 更新为 {@code REJECTED}；</li>
 *   <li>拉黑 = 一条 {@code BLOCKED} 行（仅拉黑方持有，只有本人可取消）。</li>
 * </ul>
 */
@Getter
@Setter
@TableName("friend_relations")
public class FriendRelation {

    @TableId(type = IdType.INPUT)
    private String id;
    private String userId;
    private String targetUserId;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum Status {
        PENDING, ACCEPTED, REJECTED, BLOCKED
    }
}
