package com.isles.common.error;

import com.isles.common.api.FieldErrorDetail;

import java.util.List;

/**
 * 业务异常：携带稳定错误码与可选字段级明细，由 {@link GlobalExceptionHandler} 统一转换为错误信封。
 *
 * <p>禁止在业务代码中直接返回内部异常信息，用户可见文案由 {@link ErrorCode} 或调用方显式给出。
 */
public class AppException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<FieldErrorDetail> details;

    public AppException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), List.of());
    }

    public AppException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    public AppException(ErrorCode errorCode, String message, List<FieldErrorDetail> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public static AppException of(ErrorCode errorCode) {
        return new AppException(errorCode);
    }

    public static AppException of(ErrorCode errorCode, String message) {
        return new AppException(errorCode, message);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public List<FieldErrorDetail> getDetails() {
        return details;
    }
}
