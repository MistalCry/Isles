import { ref } from 'vue'
import { defineStore } from 'pinia'
import { Client, type IMessage } from '@stomp/stompjs'
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
        syncAfterReconnect()
      },
      onWebSocketClose: () => {
        connected.value = false
      },
      onStompError: (frame) => {
        lastError.value = frame.headers?.message || '实时通道连接失败'
        connected.value = false
        connecting.value = false
      },
      onWebSocketError: () => {
        lastError.value = '实时通道连接失败，将自动重试'
        connecting.value = false
      },
    })
    client.activate()
  }

  function disconnect(): void {
    if (client) {
      void client.deactivate()
      client = null
    }
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
  }
})
