package com.isles.manage.dto;

import jakarta.validation.constraints.Size;

/**
 * 拒绝加入申请，可附原因。
 */
public record RejectJoinRequest(@Size(max = 200, message = "原因最长 200 个字符") String note) {
}
