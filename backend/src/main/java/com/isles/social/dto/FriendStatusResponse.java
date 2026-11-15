package com.isles.social.dto;

/**
 * 两个用户之间的关系状态（{@code GET /api/v1/friends/{userId}/status}）。
 *
 * <p>供用户卡片/私信按钮判断展示：是否好友、是否有待处理申请、拉黑关系。
 * 拉黑是单向且静默的：{@code blocked} 表示"我拉黑了对方"，{@code blockedBy} 表示"对方拉黑了我"。
 *
 * @param userId          目标用户 ID
 * @param friend          是否已是好友
 * @param pendingIncoming 对方是否已向我发起待处理申请
 * @param pendingOutgoing 我是否已向对方发起待处理申请
 * @param blocked         我是否拉黑了对方
 * @param blockedBy       对方是否拉黑了我
 */
public record FriendStatusResponse(
        String userId,
        boolean friend,
        boolean pendingIncoming,
        boolean pendingOutgoing,
        boolean blocked,
        boolean blockedBy) {
}
