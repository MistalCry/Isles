package com.isles.common.error;

import org.springframework.http.HttpStatus;

/**
 * 稳定错误码枚举（docs/05 §1.2）。
 *
 * <p>错误码是对外契约：只增不改。每个错误码绑定 HTTP 状态与默认用户可读文案，
 * 日志排查依赖稳定的 code，前端依赖 code 做分支处理。
 */
public enum ErrorCode {

    // 400
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "参数校验失败"),
    MEDIA_INVALID_TYPE(HttpStatus.BAD_REQUEST, "不支持的文件类型"),

    // 401
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "未登录或登录已失效"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "令牌已过期"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "用户名或密码错误"),

    // 403
    FORBIDDEN(HttpStatus.FORBIDDEN, "无权执行该操作"),
    CHANNEL_MUTED(HttpStatus.FORBIDDEN, "你已被禁言，暂时无法操作"),
    NOT_JOINED(HttpStatus.FORBIDDEN, "尚未加入该频道"),

    // 404
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    CHANNEL_NOT_FOUND(HttpStatus.NOT_FOUND, "频道不存在或不可见"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "用户不存在"),
    POST_NOT_FOUND(HttpStatus.NOT_FOUND, "帖子不存在或不可见"),
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "评论不存在或不可见"),
    BOARD_NOT_FOUND(HttpStatus.NOT_FOUND, "板块不存在"),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "通知不存在"),
    INVITE_INVALID(HttpStatus.NOT_FOUND, "邀请链接无效"),

    // 405
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),

    // 409
    USERNAME_TAKEN(HttpStatus.CONFLICT, "用户名已被占用"),
    ALREADY_JOINED(HttpStatus.CONFLICT, "你已经加入该频道"),
    ALREADY_LIKED(HttpStatus.CONFLICT, "已经点过赞"),
    ALREADY_APPLIED(HttpStatus.CONFLICT, "已提交过加入申请"),
    CONFLICT(HttpStatus.CONFLICT, "资源冲突"),

    // 413
    MEDIA_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "文件大小超出限制"),

    // 422
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT, "业务规则校验失败"),
    INVITE_EXPIRED(HttpStatus.UNPROCESSABLE_CONTENT, "邀请链接已过期"),
    INVITE_EXHAUSTED(HttpStatus.UNPROCESSABLE_CONTENT, "邀请链接使用次数已达上限"),

    // 429
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试"),

    // 500
    MEDIA_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "文件保存失败"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务端异常，请稍后重试");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
