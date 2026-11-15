package com.isles.realtime;

import com.isles.realtime.dto.RealtimeEvent;

/**
 * 实时推送统一出口（docs/02 §5.4）：业务模块只依赖本接口，不直接使用 {@code SimpMessagingTemplate}。
 *
 * <p>约定：
 * <ul>
 *   <li>私信事件 → {@link #QUEUE_DM}；</li>
 *   <li>通知/好友事件 → {@link #QUEUE_NOTIFICATIONS}；</li>
 *   <li>推送是"尽力而为"的下行通道，失败不回滚业务、不影响持久化；客户端断线后按 REST 游标补拉。</li>
 * </ul>
 * 其它模块（如 notification）可直接注入本接口做实时推送，无需感知 STOMP 细节。
 */
public interface RealtimePublisher {

    /** 私信事件队列（客户端订阅 {@code /user/queue/dm}）。 */
    String QUEUE_DM = "/queue/dm";

    /** 个人通知队列（客户端订阅 {@code /user/queue/notifications}）。 */
    String QUEUE_NOTIFICATIONS = "/queue/notifications";

    /**
     * 向指定用户推送私信事件。
     */
    void publishDm(String userId, RealtimeEvent event);

    /**
     * 向指定用户推送通知事件（好友申请/处理结果等）。
     */
    void publishNotification(String userId, RealtimeEvent event);

    /**
     * 向指定用户的指定队列推送（仅允许本接口已声明的队列，防止目的地注入）。
     */
    void publishToUser(String userId, String queue, RealtimeEvent event);
}
