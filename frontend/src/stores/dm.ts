import { t } from '@/i18n'
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { dmApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { toDmMessage } from '@/api/adapters'
import type { DmConversation, DmMessage } from '@/types/models'
import type { SendDmMessagePayload } from '@/api/dm'

/**
 * 私信状态：会话列表、当前会话消息、未读数。
 * 实时消息（/user/queue/dm）由 stores/realtime 转给 handleIncoming；断线后 REST 补拉。
 */
export const useDmStore = defineStore('dm', () => {
  const conversations = ref<DmConversation[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const hasMore = ref(false)
  const unreadCount = ref(0)
  const currentConversationId = ref('')

  const messages = ref<DmMessage[]>([])
  const messagesLoading = ref(false)
  const messagesError = ref<string | null>(null)
  const messagesHasMore = ref(false)
  const sending = ref(false)

  let conversationCursor: string | null = null
  let messageCursor: string | null = null
  let conversationSeq = 0
  let messageSeq = 0

  const currentConversation = computed(
    () => conversations.value.find((item) => item.id === currentConversationId.value) ?? null,
  )

  async function fetchConversations(reset = true): Promise<void> {
    if (reset) {
      conversationCursor = null
      loading.value = true
      error.value = null
    }
    const seq = ++conversationSeq
    try {
      const page = await dmApi.getConversations(reset ? null : conversationCursor)
      if (seq !== conversationSeq) return
      conversations.value = reset ? page.items : [...conversations.value, ...page.items]
      conversationCursor = page.nextCursor ?? null
      hasMore.value = Boolean(conversationCursor)
      void refreshUnread()
    } catch (err) {
      if (seq !== conversationSeq) return
      error.value = resolveErrorMessage(err, t('dm.share.loadFailed'))
    } finally {
      if (seq === conversationSeq) loading.value = false
    }
  }

  async function loadMoreConversations(): Promise<void> {
    if (!hasMore.value || loading.value) return
    await fetchConversations(false)
  }

  async function refreshUnread(): Promise<void> {
    unreadCount.value = await dmApi.getUnreadCount()
  }

  async function createConversation(userId: string): Promise<DmConversation> {
    const conversation = await dmApi.createConversation(userId)
    upsertConversation(conversation)
    return conversation
  }

  /** 打开与指定用户的会话：已存在直接复用，否则创建。 */
  async function openConversationWithUser(userId: string): Promise<DmConversation> {
    const existing = conversations.value.find((item) => item.peer.id === userId)
    if (existing) return existing
    return createConversation(userId)
  }

  function upsertConversation(conversation: DmConversation): void {
    const index = conversations.value.findIndex((item) => item.id === conversation.id)
    if (index >= 0) {
      conversations.value[index] = { ...conversations.value[index], ...conversation }
    } else {
      conversations.value = [conversation, ...conversations.value]
    }
    conversations.value = [...conversations.value].sort(
      (a, b) => new Date(b.updatedAt ?? 0).getTime() - new Date(a.updatedAt ?? 0).getTime(),
    )
  }

  async function loadMessages(conversationId: string, reset = true): Promise<void> {
    currentConversationId.value = conversationId
    if (reset) {
      messages.value = []
      messageCursor = null
      messagesHasMore.value = false
      messagesError.value = null
      messagesLoading.value = true
    }
    const seq = ++messageSeq
    try {
      const page = await dmApi.getMessages(conversationId, reset ? null : messageCursor)
      if (seq !== messageSeq) return
      messages.value = reset ? page.items : [...page.items, ...messages.value]
      messageCursor = page.nextCursor ?? null
      messagesHasMore.value = Boolean(messageCursor)
      if (reset) {
        void markConversationRead(conversationId)
      }
    } catch (err) {
      if (seq !== messageSeq) return
      messagesError.value = resolveErrorMessage(err, t('dm.loadFailed'))
    } finally {
      if (seq === messageSeq) messagesLoading.value = false
    }
  }

  async function loadOlderMessages(): Promise<void> {
    if (!messagesHasMore.value || messagesLoading.value || !currentConversationId.value) return
    await loadMessages(currentConversationId.value, false)
  }

  async function sendMessage(conversationId: string, payload: SendDmMessagePayload): Promise<DmMessage> {
    sending.value = true
    try {
      const message = await dmApi.sendMessage(conversationId, payload)
      appendMessage(message)
      const conversation = conversations.value.find((item) => item.id === conversationId)
      if (conversation) {
        conversation.lastMessage = message
        conversation.updatedAt = message.createdAt
      }
      return message
    } finally {
      sending.value = false
    }
  }

  async function markConversationRead(conversationId: string): Promise<void> {
    const conversation = conversations.value.find((item) => item.id === conversationId)
    if (conversation && conversation.unreadCount > 0) {
      unreadCount.value = Math.max(0, unreadCount.value - conversation.unreadCount)
      conversation.unreadCount = 0
    }
    await dmApi.markConversationRead(conversationId)
  }

  function appendMessage(message: DmMessage): void {
    if (!message.id || messages.value.some((item) => item.id === message.id)) return
    if (message.conversationId === currentConversationId.value) {
      messages.value = [...messages.value, message]
    }
  }

  /** 实时私信：兼容裸消息与 { message } 包装；不在会话列表的会话触发补拉。 */
  function handleIncoming(payload: unknown): void {
    const raw = (payload ?? {}) as Record<string, unknown>
    const source = raw.message ?? raw.data ?? payload
    const message = toDmMessage(source)
    if (!message.id) {
      void fetchConversations(true)
      return
    }
    if (!message.conversationId) return
    const conversation = conversations.value.find((item) => item.id === message.conversationId)
    if (!conversation) {
      void fetchConversations(true)
      return
    }
    conversation.lastMessage = message
    conversation.updatedAt = message.createdAt
    if (message.conversationId === currentConversationId.value) {
      appendMessage(message)
      if (message.senderId !== conversation.peer.id) {
        void markConversationRead(message.conversationId)
      }
    } else if (message.senderId !== conversation.peer.id) {
      conversation.unreadCount += 1
      unreadCount.value += 1
    }
    conversations.value = [...conversations.value].sort(
      (a, b) => new Date(b.updatedAt ?? 0).getTime() - new Date(a.updatedAt ?? 0).getTime(),
    )
  }

  function clear(): void {
    conversations.value = []
    messages.value = []
    currentConversationId.value = ''
    unreadCount.value = 0
    error.value = null
    messagesError.value = null
    hasMore.value = false
    messagesHasMore.value = false
    conversationCursor = null
    messageCursor = null
    conversationSeq += 1
    messageSeq += 1
  }

  return {
    conversations,
    currentConversation,
    currentConversationId,
    loading,
    error,
    hasMore,
    unreadCount,
    messages,
    messagesLoading,
    messagesError,
    messagesHasMore,
    sending,
    fetchConversations,
    loadMoreConversations,
    refreshUnread,
    createConversation,
    openConversationWithUser,
    loadMessages,
    loadOlderMessages,
    sendMessage,
    markConversationRead,
    handleIncoming,
    clear,
  }
})
