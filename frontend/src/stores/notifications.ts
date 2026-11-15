import { ref } from 'vue'
import { defineStore } from 'pinia'
import { notificationsApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { toNotification } from '@/api/adapters'
import type { NotificationItem, NotificationQuery } from '@/types/models'

/**
 * 通知中心状态：列表、未读角标、已读；实时消息由 stores/realtime 转发到 handleIncoming。
 */
export const useNotificationStore = defineStore('notifications', () => {
  const items = ref<NotificationItem[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const unreadCount = ref(0)
  const hasMore = ref(false)

  let cursor: string | null = null
  let activeQuery: NotificationQuery = {}
  let requestSeq = 0

  async function fetchNotifications(query: NotificationQuery = {}, reset = true): Promise<void> {
    if (reset) {
      activeQuery = { ...query }
      cursor = null
      items.value = []
      hasMore.value = false
      error.value = null
    }
    const seq = ++requestSeq
    loading.value = true
    try {
      const page = await notificationsApi.listNotifications({
        ...activeQuery,
        cursor: reset ? null : cursor,
      })
      if (seq !== requestSeq) return
      items.value = reset ? page.items : [...items.value, ...page.items]
      cursor = page.nextCursor ?? null
      hasMore.value = Boolean(cursor)
      void refreshUnread()
    } catch (err) {
      if (seq !== requestSeq) return
      error.value = resolveErrorMessage(err, '通知加载失败')
    } finally {
      if (seq === requestSeq) loading.value = false
    }
  }

  async function loadMore(): Promise<void> {
    if (!hasMore.value || loading.value) return
    await fetchNotifications(activeQuery, false)
  }

  async function refreshUnread(): Promise<void> {
    unreadCount.value = await notificationsApi.getUnreadCount()
  }

  async function markRead(id: string): Promise<void> {
    const target = items.value.find((item) => item.id === id)
    if (!target || target.read) return
    target.read = true
    unreadCount.value = Math.max(0, unreadCount.value - 1)
    await notificationsApi.markNotificationRead(id)
  }

  async function markAllRead(): Promise<void> {
    await notificationsApi.markAllNotificationsRead()
    items.value.forEach((item) => {
      item.read = true
    })
    unreadCount.value = 0
  }

  /** 实时通知（/user/queue/notifications）：兼容裸通知对象与 { notification } 包装。 */
  function handleIncoming(payload: unknown): void {
    const raw = (payload ?? {}) as Record<string, unknown>
    const source = raw.notification ?? raw.data ?? payload
    const item = toNotification(source)
    if (!item.id) return
    if (!items.value.some((existing) => existing.id === item.id)) {
      items.value = [item, ...items.value]
      unreadCount.value += 1
    }
  }

  function clear(): void {
    items.value = []
    unreadCount.value = 0
    hasMore.value = false
    cursor = null
    error.value = null
    loading.value = false
    requestSeq += 1
  }

  return {
    items,
    loading,
    error,
    unreadCount,
    hasMore,
    fetchNotifications,
    loadMore,
    refreshUnread,
    markRead,
    markAllRead,
    handleIncoming,
    clear,
  }
})
