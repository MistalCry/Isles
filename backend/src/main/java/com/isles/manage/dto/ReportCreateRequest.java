package com.isles.manage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建举报（FR-ITR-03）：帖子或评论。
 */
public record ReportCreateRequest(
        @NotBlank(message = "举报目标类型不能为空")
        @Pattern(regexp = "POST|COMMENT", message = "举报目标类型非法")
        String targetType,

        @NotBlank(message = "举报目标不能为空")
        String targetId,

        @NotBlank(message = "举报原因不能为空")
        @Pattern(regexp = "SPAM|ABUSE|PORN|ILLEGAL|OTHER", message = "举报原因非法")
        String reason,

        @Size(max = 500, message = "补充说明最长 500 个字符")
        String description) {
}
