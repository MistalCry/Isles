package com.isles.common.error;

import com.isles.common.api.ApiError;
import com.isles.common.api.ErrorResponse;
import com.isles.common.api.FieldErrorDetail;
import com.isles.common.ratelimit.RateLimitExceededException;
import com.isles.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * 全局异常处理：把所有异常收敛为统一错误信封（docs/05 §1.2），不向客户端泄露内部实现细节。
 *
 * <p>日志与响应分离：响应只给稳定错误码 + 用户可读文案；堆栈/上下文只进日志，且含 requestId 便于排查。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimited(RateLimitExceededException ex,
                                                           HttpServletRequest request) {
        ApiError error = new ApiError(ErrorCode.RATE_LIMITED.name(), ex.getMessage(), List.of(),
                requestId(request));
        return ResponseEntity.status(ErrorCode.RATE_LIMITED.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(new ErrorResponse(error));
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleAppException(AppException ex, HttpServletRequest request) {
        ErrorCode code = ex.getErrorCode();
        if (code.httpStatus().is5xxServerError()) {
            log.error("业务异常(5xx) requestId={} code={}", requestId(request), code.name(), ex);
        } else if (code == ErrorCode.FORBIDDEN || code == ErrorCode.CHANNEL_MUTED) {
            log.warn("权限校验拒绝 requestId={} code={} message={}", requestId(request), code.name(), ex.getMessage());
        }
        return build(code, ex.getMessage(), ex.getDetails(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                      HttpServletRequest request) {
        List<FieldErrorDetail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorDetail(error.getField(), error.getDefaultMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(), details, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        List<FieldErrorDetail> details = ex.getConstraintViolations().stream()
                .map(violation -> new FieldErrorDetail(violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(), details, request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            ServletRequestBindingException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
        log.debug("请求参数不合法 requestId={} message={}", requestId(request), ex.getMessage());
        return build(ErrorCode.VALIDATION_ERROR, "请求参数或请求体格式不正确", List.of(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex,
                                                             HttpServletRequest request) {
        return build(ErrorCode.MEDIA_TOO_LARGE, ErrorCode.MEDIA_TOO_LARGE.defaultMessage(), List.of(), request);
    }

    @ExceptionHandler({HttpMediaTypeNotSupportedException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ErrorResponse> handleUnsupportedRequest(Exception ex, HttpServletRequest request) {
        return build(ErrorCode.VALIDATION_ERROR, "请求内容类型或表单字段不正确", List.of(), request);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateKey(DuplicateKeyException ex, HttpServletRequest request) {
        // 唯一约束兜底：并发重复提交时由数据库保证幂等，这里转成稳定 409
        log.warn("唯一约束冲突 requestId={} message={}", requestId(request), ex.getMostSpecificCause().getMessage());
        return build(ErrorCode.CONFLICT, "资源已存在或重复提交", List.of(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage(), List.of(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return build(ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND.defaultMessage(), List.of(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                  HttpServletRequest request) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.defaultMessage(), List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        // 未预期异常：完整堆栈只进日志，响应不暴露内部细节
        log.error("未处理异常 requestId={}", requestId(request), ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), List.of(), request);
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message, List<FieldErrorDetail> details,
                                                HttpServletRequest request) {
        ApiError error = new ApiError(code.name(), message, details, requestId(request));
        // 显式声明 JSON：SSE 接口（Accept: text/event-stream）出错时也能返回统一错误信封而非 406/空响应
        return ResponseEntity.status(code.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(error));
    }

    private String requestId(HttpServletRequest request) {
        return RequestIdFilter.resolve(request);
    }
}
