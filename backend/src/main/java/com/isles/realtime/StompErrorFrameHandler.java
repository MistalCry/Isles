package com.isles.realtime;

import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

import java.nio.charset.StandardCharsets;

/**
 * STOMP ERROR 帧增强：把 {@link StompAccessDeniedException#getCode()} 写进 {@code code} 原生头。
 *
 * <p>Spring 默认的 ERROR 帧只有 {@code message}，前端只能靠中文文案做分支（脆弱）。
 * 这里保留默认行为，仅额外补一个稳定的 {@code code} 头；非本类异常仍走父类默认处理。
 */
public class StompErrorFrameHandler extends StompSubProtocolErrorHandler {

    private static final String HEADER_CODE = "code";

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        Throwable cause = unwrap(ex);
        if (!(cause instanceof StompAccessDeniedException denied)) {
            return super.handleClientMessageProcessingError(clientMessage, ex);
        }
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(denied.getMessage() == null ? denied.getCode() : denied.getMessage());
        accessor.setNativeHeader(HEADER_CODE, denied.getCode());
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(
                (denied.getMessage() == null ? denied.getCode() : denied.getMessage())
                        .getBytes(StandardCharsets.UTF_8),
                accessor.getMessageHeaders());
    }

    /**
     * 异常可能被 Spring 包一层（{@code MessagingException} 套 {@code MessagingException}），
     * 这里沿 cause 链找第一个 {@link StompAccessDeniedException}。
     */
    private Throwable unwrap(Throwable ex) {
        Throwable current = ex;
        int guard = 0;
        while (current != null && guard++ < 8) {
            if (current instanceof StompAccessDeniedException) {
                return current;
            }
            current = current.getCause();
        }
        return ex;
    }
}
