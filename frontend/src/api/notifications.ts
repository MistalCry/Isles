import type { NotificationItem, NotificationQuery, PageResult } from '@/types/models'
import { http } from './client'
import { extractPage } from './normalize'
import { toNotification } from './adapters'

/**
 * 通知与消息中心（M1 已提供列表/已读；未读数接口为 WP1 契约假设，
 * 若后端暂未提供则回退为“最多 100 条未读列表长度”，不伪造数据）。
 */
export async function listNotifications(query: NotificationQuery = {}): Promise<PageResult<NotificationItem>> {
  const { data } = await http.get('/notifications', {
    params: {
      channelId: query.channelId || undefined,
      type: query.type || undefined,
      unreadOnly: query.unreadOnly ?? undefined,
      cursor: query.cursor ?? undefined,
      limit: query.limit ?? 20,
    },
  })
  const page = extractPage<unknown>(data)
  return { ...page, items: page.items.map(toNotification) }
}

export async function getUnreadCount(): Promise<number> {
  try {
    const { data } = await http.get('/notifications/unread-count', { silent: true })
    if (typeof data === 'number') return data
    const record = (data ?? {}) as Record<string, unknown>
    const value = record.count ?? record.unreadCount ?? record.total
    return Number(value) || 0
  } catch {
    try {
      const page = await listNotifications({ unreadOnly: true, limit: 100 })
      return page.items.length
    } catch {
      return 0
    }
  }
}

export async function markNotificationRead(id: string): Promise<NotificationItem | null> {
  try {
    const { data } = await http.post(`/notifications/${encodeURIComponent(id)}/read`, undefined, {
      silent: true,
    })
    return data ? toNotification(data) : null
  } catch {
    return null
  }
}

export async function markAllNotificationsRead(): Promise<void> {
  await http.post('/notifications/read-all', undefined, { silent: true })
}
