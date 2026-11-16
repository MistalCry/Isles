import { t } from '@/i18n'
import { ref } from 'vue'
import { defineStore } from 'pinia'
import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'
import { useAuthStore } from './auth'
import { useDmStore } from './dm'
import { useNotificationStore } from './notifications'

/**
 * WebSocket/STOMP 实时通道（docs/02 §5.4、docs/07）：
 * - 端点 /ws，CONNECT 头携带 Bearer accessToken；
 * - 订阅 /user/queue/dm 与 /user/queue/notifications；
 * - 断线由 stompjs 指数退避重连；重连成功后回调刷新未读与私信列表（REST 补拉兜底）。
 */
export const useRealtimeStore = defineStore('realtime', () => {
  const connected = ref(false)
  const connecting = ref(false)
  const lastError = ref<string | null>(null)
  const lastConnectedAt = ref<number | null>(null)

  let client: Client | null = null
  /** 连接建立前登记的订阅：连上后自动补订阅（语音房等场景会在连接中就订阅） */
  const pendingSubscriptions: Array<{
    destination: string
    handler: (body: unknown) => void
    handle?: StompSubscription
  }> = []

  function parseBody(message: IMessage): unknown {
    try {
      return JSON.parse(message.body)
    } catch {
      return null
    }
  }

  function syncAfterReconnect(): void {
    const dm = useDmStore()
    const notifications = useNotificationStore()
    void dm.fetchConversations(true)
    void notifications.refreshUnread()
  }

  function connect(): void {
    const auth = useAuthStore()
    if (!auth.accessToken || typeof window === 'undefined') return
    const connectHeaders = { Authorization: `Bearer ${auth.accessToken}` }
    if (client) {
      // 令牌刷新后更新重连使用的 CONNECT 头
      client.connectHeaders = connectHeaders
      if (client.active || client.connected) return
    }

    connecting.value = true
    lastError.value = null
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const brokerURL = `${protocol}://${window.location.host}/ws`

    client = new Client({
      brokerURL,
      connectHeaders,
      reconnectDelay: 3000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        connected.value = true
        connecting.value = false
        lastConnectedAt.value = Date.now()
        client?.subscribe('/user/queue/dm', (message) => {
          useDmStore().handleIncoming(parseBody(message))
        })
        client?.subscribe('/user/queue/notifications', (message) => {
          useNotificationStore().handleIncoming(parseBody(message))
        })
        for (const entry of pendingSubscriptions) {
          if (entry.handle) continue
          entry.handle = client?.subscribe(entry.destination, (message) => entry.handler(parseBody(message)))
        }
        syncAfterReconnect()
      },
      onWebSocketClose: () => {
        connected.value = false
      },
      onStompError: (frame) => {
        lastError.value = frame.headers?.message || t('realtime.connectFailed')
        connected.value = false
        connecting.value = false
      },
      onWebSocketError: () => {
        lastError.value = t('realtime.connectRetrying')
        connecting.value = false
      },
    })
    client.activate()
  }

  /**
   * 订阅任意主题（语音房信令/状态）。
   * 未连接时先登记，CONNECTED 后自动补订阅；返回取消函数。
   */
  function subscribe(destination: string, handler: (body: unknown) => void): () => void {
    const entry = { destination, handler } as (typeof pendingSubscriptions)[number]
    if (client?.connected) {
      entry.handle = client.subscribe(destination, (message) => handler(parseBody(message)))
    } else {
      pendingSubscriptions.push(entry)
    }
    return () => {
      entry.handle?.unsubscribe()
      const index = pendingSubscriptions.indexOf(entry)
      if (index >= 0) pendingSubscriptions.splice(index, 1)
    }
  }

  /** 发送到 /app/**；未连接时直接丢弃（语音房会提示信令未就绪） */
  function publish(destination: string, body: unknown): boolean {
    if (!client?.connected) return false
    client.publish({ destination, body: JSON.stringify(body) })
    return true
  }

  function disconnect(): void {
    if (client) {
      void client.deactivate()
      client = null
    }
    for (const entry of pendingSubscriptions) entry.handle = undefined
    connected.value = false
    connecting.value = false
  }

  return {
    connected,
    connecting,
    lastError,
    lastConnectedAt,
    connect,
    disconnect,
    subscribe,
    publish,
  }
})
