package com.isles.channel.dto;

/**
 * 使用邀请链接的结果：{@code JOINED} 已加入；{@code PENDING} 已提交加入申请，等待审批。
 */
public record InviteAcceptResponse(String status, String channelId, String joinRequestId) {
}
