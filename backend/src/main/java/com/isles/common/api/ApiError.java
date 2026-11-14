package com.isles.common.api;

import java.util.List;

/**
 * 错误信封内层结构（docs/05 §1.2）：
 * <pre>{@code
 * { "error": { "code": "...", "message": "...", "details": [], "requestId": "..." } }
 * }</pre>
 */
public record ApiError(String code, String message, List<FieldErrorDetail> details, String requestId) {

    public ApiError {
        details = details == null ? List.of() : List.copyOf(details);
    }
}
