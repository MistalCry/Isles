package com.isles.realtime;

import org.springframework.messaging.MessagingException;

/**
 * STOMP 目的地拒绝异常：带稳定 {@code code}，由 {@link StompErrorFrameHandler} 写进 ERROR 帧的
 * {@code code} 原生头，前端可按 code 分支（例如提示「请先进入房间」而不是笼统的「连接失败」）。
 *
 * <p>code 取值与 REST 错误码保持一致（复用 {@code ErrorCode} 名称），便于前后端共用一套判断。
 */
public class StompAccessDeniedException extends MessagingException {

    /** 稳定错误码（如 {@code NOT_IN_ROOM} / {@code ROOM_CLOSED} / {@code UNAUTHORIZED}）。 */
    private final String code;

    public StompAccessDeniedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
