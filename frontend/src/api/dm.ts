import type { DmConversation, DmMessage, DmMessageType, MediaItem, PageResult } from '@/types/models'
import { http } from './client'
import { extractPage } from './normalize'
import { toDmConversation, toDmMessage } from './adapters'

/**
 * 私信接口（WP2 契约假设，docs/03 FR-DM-01～05 / docs/05 §2.9）：
 * GET /dm/conversations、POST /dm/conversations、GET/POST /dm/conversations/{id}/messages、
 * POST /dm/conversations/{id}/read、GET /dm/unread-count。
 * 实时消息通过 STOMP /user/queue/dm 推送，REST 列表作为断线补拉兜底。
 */
export interface SendDmMessagePayload {
  type: DmMessageType
  content?: string
  media?: MediaItem[]
  postId?: string
  clientMessageId?: string
}

export async function getConversations(cursor?: string | null, limit = 20): Promise<PageResult<DmConversation>> {
  const { data } = await http.get('/dm/conversations', {
    params: { cursor: cursor ?? undefined, limit },
  })
  const page = extractPage<unknown>(data)
  return { ...page, items: page.items.map(toDmConversation) }
}

/** 与指定用户创建（或复用）私信会话，返回会话信息。 */
export async function createConversation(userId: string): Promise<DmConversation> {
  const { data } = await http.post('/dm/conversations', { userId })
  return toDmConversation(data)
}

export async function getMessages(
  conversationId: string,
  cursor?: string | null,
  limit = 30,
): Promise<PageResult<DmMessage>> {
  const { data } = await http.get(`/dm/conversations/${encodeURIComponent(conversationId)}/messages`, {
    params: { cursor: cursor ?? undefined, limit },
  })
  const page = extractPage<unknown>(data)
  return { ...page, items: page.items.map(toDmMessage) }
}

export async function sendMessage(
  conversationId: string,
  payload: SendDmMessagePayload,
): Promise<DmMessage> {
  const { data } = await http.post(
    `/dm/conversations/${encodeURIComponent(conversationId)}/messages`,
    payload,
  )
  return toDmMessage(data)
}

export async function markConversationRead(conversationId: string): Promise<void> {
  await http.post(`/dm/conversations/${encodeURIComponent(conversationId)}/read`, undefined, {
    silent: true,
  })
}

export async function getUnreadCount(): Promise<number> {
  try {
    const { data } = await http.get('/dm/unread-count', { silent: true })
    if (typeof data === 'number') return data
    const record = (data ?? {}) as Record<string, unknown>
    return Number(record.count ?? record.unreadCount ?? record.total) || 0
  } catch {
    return 0
  }
}
