package com.isles.manage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 举报处置（FR-ITR-03 / FR-PADM-02）。
 *
 * <p>status=REJECTED 时 action 必须为 NONE；ACCEPTED 时可选择删除内容或禁言作者。
 */
public record HandleReportRequest(
        @NotBlank(message = "处置结果不能为空")
        @Pattern(regexp = "ACCEPTED|REJECTED", message = "处置结果非法")
        String status,

        @Pattern(regexp = "NONE|DELETE_CONTENT|MUTE_AUTHOR", message = "处置动作非法")
        String action,

        @Size(max = 500, message = "处置说明最长 500 个字符")
        String note,

        Long muteDurationSeconds) {
}
