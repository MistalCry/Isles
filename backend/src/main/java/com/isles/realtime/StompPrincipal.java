package com.isles.realtime;

import java.security.Principal;

/**
 * STOMP 连接的 Principal：name 即 userId，供 {@code convertAndSendToUser} 定位用户目的地。
 */
public record StompPrincipal(String userId) implements Principal {

    @Override
    public String getName() {
        return userId;
    }
}
