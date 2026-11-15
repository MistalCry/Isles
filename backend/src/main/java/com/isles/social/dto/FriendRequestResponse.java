package com.isles.social.dto;

import java.time.Instant;

/**
 * 好友申请条目（发起/待处理列表/同意/拒绝的返回体）。
 *
 * <p>{@code fromUser}/{@code toUser} 同时给出申请双方，前端可直接渲染"谁申请了谁"；
 * 相对当前用户的视角由 {@code direction} 表达。
 *
 * @param id        关系行 ID（申请 ID），同意/拒绝接口使用
 * @param direction 相对当前用户的方向：{@code INCOMING}（别人申请我）/ {@code OUTGOING}（我申请别人）
 * @param status    关系状态：{@code PENDING}/{@code ACCEPTED}/{@code REJECTED}
 * @param fromUser  申请发起方
 * @param toUser    申请接收方
 * @param createdAt 申请发起时间
 */
public record FriendRequestResponse(
        String id,
        String direction,
        String status,
        FriendUserBrief fromUser,
        FriendUserBrief toUser,
        Instant createdAt) {
}
