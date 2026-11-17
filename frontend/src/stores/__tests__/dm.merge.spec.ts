import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

// 私信「拉取覆盖推送」（docs/13 P0-5）：
// loadMessages(reset) 期间收到 WS 推送时，旧快照不能把刚到的消息抹掉。
const mocks = vi.hoisted(() => ({
  getMessages: vi.fn(),
  getConversations: vi.fn(),
  getUnreadCount: vi.fn(),
  markConversationRead: vi.fn(),
}))

vi.mock('@/api', () => ({
  dmApi: {
    getMessages: mocks.getMessages,
    getConversations: mocks.getConversations,
    getUnreadCount: mocks.getUnreadCount,
    markConversationRead: mocks.markConversationRead,
    sendMessage: vi.fn(),
    createConversation: vi.fn(),
  },
}))

import { useDmStore } from '@/stores/dm'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

function message(id: string, content: string) {
  return {
    id,
    conversationId: 'conv-1',
    senderId: 'peer',
    content,
    createdAt: '2026-11-17T10:00:00.000Z',
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  mocks.getUnreadCount.mockResolvedValue(0)
  mocks.markConversationRead.mockResolvedValue(undefined)
  mocks.getConversations.mockResolvedValue({ items: [], nextCursor: null })
})

describe('dm store：reset 拉取与 WS 推送', () => {
  it('拉取期间收到的推送消息不会被旧快照抹掉', async () => {
    const pending = deferred<unknown>()
    mocks.getMessages.mockReturnValueOnce(pending.promise)

    const store = useDmStore()
    store.conversations = [{ id: 'conv-1', peer: { id: 'peer' }, unreadCount: 0 } as never]

    const loading = store.loadMessages('conv-1', true)
    // 请求还没回来，推送先到
    store.handleIncoming({ message: message('m-new', '刚收到的新消息') })

    pending.resolve({ items: [message('m-old', '更早的一条')], nextCursor: null })
    await loading

    const contents = store.messages.map((item) => item.content)
    expect(contents).toContain('更早的一条')
    expect(contents).toContain('刚收到的新消息')
    // 新消息排在末尾（消息列表是「旧 → 新」）
    expect(contents[contents.length - 1]).toBe('刚收到的新消息')
  })

  it('服务端已包含该消息时不会重复', async () => {
    const pending = deferred<unknown>()
    mocks.getMessages.mockReturnValueOnce(pending.promise)

    const store = useDmStore()
    store.conversations = [{ id: 'conv-1', peer: { id: 'peer' }, unreadCount: 0 } as never]

    const loading = store.loadMessages('conv-1', true)
    store.handleIncoming({ message: message('m-1', '同一条消息') })

    pending.resolve({ items: [message('m-1', '同一条消息')], nextCursor: null })
    await loading

    expect(store.messages.filter((item) => item.id === 'm-1')).toHaveLength(1)
  })
})
