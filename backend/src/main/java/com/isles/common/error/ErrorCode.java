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
    BLOCKED(HttpStatus.FORBIDDEN, "由于拉黑关系，无法执行该操作"),
    CANNOT_OPERATE_OWNER(HttpStatus.FORBIDDEN, "不能对频道主执行该操作"),
    SYSTEM_ROLE_PROTECTED(HttpStatus.FORBIDDEN, "系统身份组不支持该操作"),
    PERMISSION_EXCEEDED(HttpStatus.FORBIDDEN, "不能授予或使用超过自身的权限"),
    USER_BANNED(HttpStatus.FORBIDDEN, "账号已被封禁，如有疑问请联系平台管理员"),
    ADMIN_SELF_REVOKE_FORBIDDEN(HttpStatus.FORBIDDEN, "不能撤销自己的管理员权限"),

    // 404
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    CHANNEL_NOT_FOUND(HttpStatus.NOT_FOUND, "频道不存在或不可见"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "用户不存在"),
    POST_NOT_FOUND(HttpStatus.NOT_FOUND, "帖子不存在或不可见"),
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "评论不存在或不可见"),
    BOARD_NOT_FOUND(HttpStatus.NOT_FOUND, "板块不存在"),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "通知不存在"),
    INVITE_INVALID(HttpStatus.NOT_FOUND, "邀请链接无效"),
    INVITE_NOT_FOUND(HttpStatus.NOT_FOUND, "邀请链接不存在"),
    ROLE_NOT_FOUND(HttpStatus.NOT_FOUND, "身份组不存在"),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "频道成员不存在"),
    JOIN_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "加入申请不存在"),
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "举报记录不存在"),
    FRIEND_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "好友申请不存在或已处理"),
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND, "私信会话不存在或无权访问"),
    SETTING_NOT_FOUND(HttpStatus.NOT_FOUND, "配置项不存在"),
    PENALTY_NOT_FOUND(HttpStatus.NOT_FOUND, "处罚记录不存在"),
    ANNOUNCEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "公告不存在"),
    SENSITIVE_WORD_NOT_FOUND(HttpStatus.NOT_FOUND, "敏感词不存在"),

    // 405
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),

    // 409
    USERNAME_TAKEN(HttpStatus.CONFLICT, "用户名已被占用"),
    ALREADY_JOINED(HttpStatus.CONFLICT, "你已经加入该频道"),
    ALREADY_LIKED(HttpStatus.CONFLICT, "已经点过赞"),
    ALREADY_APPLIED(HttpStatus.CONFLICT, "已提交过加入申请"),
    JOIN_REQUEST_HANDLED(HttpStatus.CONFLICT, "该加入申请已处理"),
    REPORT_ALREADY_EXISTS(HttpStatus.CONFLICT, "你已举报过该内容，请等待处理"),
    REPORT_ALREADY_HANDLED(HttpStatus.CONFLICT, "该举报已处理"),
    SENSITIVE_WORD_DUPLICATE(HttpStatus.CONFLICT, "该敏感词已存在"),
    ALREADY_FRIENDS(HttpStatus.CONFLICT, "你们已经是好友"),
    FRIEND_REQUEST_PENDING(HttpStatus.CONFLICT, "已有待处理的好友申请"),
    CONFLICT(HttpStatus.CONFLICT, "资源冲突"),

    // 413
    MEDIA_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "文件大小超出限制"),

    // 422
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT, "业务规则校验失败"),
    INVITE_EXPIRED(HttpStatus.UNPROCESSABLE_CONTENT, "邀请链接已过期"),
    INVITE_EXHAUSTED(HttpStatus.UNPROCESSABLE_CONTENT, "邀请链接使用次数已达上限"),
    OWNER_CANNOT_LEAVE(HttpStatus.UNPROCESSABLE_CONTENT, "频道主不能退出频道，请先转让或解散频道"),
    LAST_BOARD_CANNOT_DELETE(HttpStatus.UNPROCESSABLE_CONTENT, "至少保留一个板块"),
    REPORT_TARGET_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "举报目标不存在或不可见"),
    SETTING_VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "配置值不合法"),
    PENALTY_ALREADY_ACTIVE(HttpStatus.UNPROCESSABLE_CONTENT, "该用户已有同类生效中的处罚"),
    ADMIN_LAST_SUPER_ADMIN(HttpStatus.UNPROCESSABLE_CONTENT, "至少需要保留一个超级管理员"),

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
