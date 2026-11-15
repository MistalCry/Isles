package com.isles.ai.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式回答取消登记表（SSE「停止生成」）。
 *
 * <p>按助手消息 id 登记取消标记：前端从 meta 事件拿到 messageId 后调用停止接口，
 * Provider 在读取下一个 chunk 前检查标记并提前结束。内存态即可（v1 单实例，docs/02 §10 扩展点）。
 */
@Component
public class AiStreamRegistry {

    private final Map<String, AtomicBoolean> cancellations = new ConcurrentHashMap<>();

    public void register(String messageId) {
        cancellations.put(messageId, new AtomicBoolean(false));
    }

    /**
     * 请求停止；未在生成中返回 false。
     */
    public boolean cancel(String messageId) {
        AtomicBoolean flag = messageId == null ? null : cancellations.get(messageId);
        if (flag == null) {
            return false;
        }
        flag.set(true);
        return true;
    }

    public boolean isCancelled(String messageId) {
        AtomicBoolean flag = messageId == null ? null : cancellations.get(messageId);
        return flag != null && flag.get();
    }

    public void remove(String messageId) {
        cancellations.remove(messageId);
    }
}
