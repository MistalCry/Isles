<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Search, UserFilled } from '@element-plus/icons-vue'
import { useDmStore } from '@/stores/dm'
import { formatRelativeTime } from '@/utils/format'
import type { DmConversation } from '@/types/models'
import ChatPanel from '@/components/dm/ChatPanel.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const dm = useDmStore()
const {
  conversations,
  loading,
  error,
  messages,
  messagesLoading,
  messagesError,
  messagesHasMore,
  sending,
} = storeToRefs(dm)

const keyword = ref('')
const unreadOnly = ref(false)

const selectedId = computed(() => String(route.params.id ?? ''))
const activeConversation = computed<DmConversation | null>(() => {
  const id = selectedId.value || dm.currentConversationId
  return conversations.value.find((item) => item.id === id) ?? null
})
const filteredConversations = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  return conversations.value.filter((conversation) => {
    if (unreadOnly.value && conversation.unreadCount === 0) return false
    if (!key) return true
    const name = (conversation.peer.nickname || conversation.peer.username || '').toLowerCase()
    return name.includes(key)
  })
})

function peerName(conversation: DmConversation): string {
  return conversation.peer.nickname || conversation.peer.username || '岛民'
}

function lastMessagePreview(conversation: DmConversation): string {
  const message = conversation.lastMessage
  if (!message) return '还没有消息'
  if (message.type === 'IMAGE') return '[图片]'
  if (message.type === 'POST') return `[帖子] ${message.post?.title || ''}`.trim()
  if (message.type === 'SYSTEM') return '[系统消息]'
  return message.content || ''
}

async function selectConversation(conversation: DmConversation): Promise<void> {
  if (conversation.id === selectedId.value) return
  await router.push({ name: 'message-detail', params: { id: conversation.id } })
}

async function loadConversations(): Promise<void> {
  await dm.fetchConversations(true)
}

async function retryMessages(): Promise<void> {
  if (selectedId.value) await dm.loadMessages(selectedId.value)
}

async function sendMessage(payload: Parameters<typeof dm.sendMessage>[1]): Promise<void> {
  if (!activeConversation.value) return
  try {
    await dm.sendMessage(activeConversation.value.id, payload)
  } catch {
    // 拦截器已提示
  }
}

async function loadOlder(): Promise<void> {
  await dm.loadOlderMessages()
}

function backToList(): void {
  void router.push({ name: 'messages' })
}

function goFriends(): void {
  void router.push({ name: 'friends' })
}

watch(
  selectedId,
  (id) => {
    if (id) {
      void dm.loadMessages(id)
    } else {
      dm.currentConversationId = ''
    }
  },
  { immediate: true },
)

onMounted(() => {
  void loadConversations()
})
</script>

<template>
  <div class="page messages-page">
    <header class="page-header">
      <div>
        <h1 class="page-title">我的私信</h1>
        <p class="page-subtitle">和岛民一对一聊天，支持图片与帖子分享</p>
      </div>
      <el-button :icon="UserFilled" @click="goFriends">好友管理</el-button>
    </header>

    <button v-if="selectedId" type="button" class="dm-back" @click="backToList">
      <el-icon><ArrowLeft /></el-icon>
      返回会话列表
    </button>

    <div class="dm-layout" :class="{ 'dm-layout--chat-open': Boolean(selectedId) }">
      <aside class="dm-conversations isles-card" aria-label="私信会话列表">
        <div class="dm-conversations__toolbar">
          <el-input v-model="keyword" :prefix-icon="Search" placeholder="搜索会话" clearable />
          <el-switch v-model="unreadOnly" active-text="只看未读" />
        </div>

        <LoadingState v-if="loading && conversations.length === 0" :rows="4" />
        <ErrorState v-else-if="error && conversations.length === 0" :message="error" @retry="loadConversations" />
        <EmptyState
          v-else-if="filteredConversations.length === 0"
          compact
          title="暂无会话"
          description="到用户卡片点击「私信」即可发起会话。"
        />

        <ul v-else class="dm-conversation-list">
          <li v-for="conversation in filteredConversations" :key="conversation.id">
            <button
              type="button"
              class="dm-conversation"
              :class="{ 'is-active': conversation.id === selectedId }"
              @click="selectConversation(conversation)"
            >
              <UserAvatar
                :src="conversation.peer.avatarUrl"
                :name="peerName(conversation)"
                :size="36"
              />
              <span class="dm-conversation__body">
                <span class="dm-conversation__head">
                  <strong>{{ peerName(conversation) }}</strong>
                  <time v-if="conversation.lastMessage" class="muted">
                    {{ formatRelativeTime(conversation.lastMessage.createdAt) }}
                  </time>
                </span>
                <span class="dm-conversation__preview">
                  <span class="line-clamp-1">{{ lastMessagePreview(conversation) }}</span>
                  <span v-if="conversation.unreadCount > 0" class="dm-conversation__unread">
                    {{ conversation.unreadCount > 99 ? '99+' : conversation.unreadCount }}
                  </span>
                </span>
              </span>
            </button>
          </li>
        </ul>

        <div v-if="dm.hasMore" class="dm-conversations__more">
          <el-button text type="primary" :loading="loading" @click="dm.loadMoreConversations()">
            加载更多会话
          </el-button>
        </div>
      </aside>

      <main class="dm-chat">
        <ChatPanel
          v-if="activeConversation"
          :conversation="activeConversation"
          :messages="messages"
          :loading="messagesLoading"
          :error="messagesError"
          :has-more="messagesHasMore"
          :sending="sending"
          @send="sendMessage"
          @load-older="loadOlder"
          @retry="retryMessages"
        />
        <EmptyState
          v-else
          title="选择一个会话开始聊天"
          description="或者到用户卡片中点击「私信」发起新会话。"
        >
          <template #action>
            <el-button :icon="UserFilled" @click="goFriends">查看好友</el-button>
          </template>
        </EmptyState>
      </main>
    </div>
  </div>
</template>

<style scoped lang="scss">
.dm-conversations {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.dm-conversations__toolbar {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
  padding: var(--isles-space-3);
  border-bottom: 1px solid var(--isles-border);
}

.dm-conversation-list {
  flex: 1;
  overflow-y: auto;
}

.dm-conversation {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  width: 100%;
  padding: var(--isles-space-3);
  text-align: left;
  background: transparent;
  border: none;
  border-bottom: 1px solid var(--isles-border);
  cursor: pointer;

  &:hover {
    background: var(--isles-hover);
  }

  &.is-active {
    background: var(--isles-primary-soft);
  }
}

.dm-conversation__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.dm-conversation__head {
  display: flex;
  gap: var(--isles-space-2);
  align-items: baseline;
  justify-content: space-between;

  strong {
    overflow: hidden;
    font-size: var(--isles-font-sm);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  time {
    flex: none;
    font-size: 10px;
  }
}

.dm-conversation__preview {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);

  .line-clamp-1 {
    flex: 1;
    min-width: 0;
  }
}

.dm-conversation__unread {
  flex: none;
  min-width: 16px;
  padding: 0 5px;
  color: #fff;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
  background: var(--isles-danger);
  border-radius: var(--isles-radius-pill);
}

.dm-conversations__more {
  padding: var(--isles-space-2);
  text-align: center;
  border-top: 1px solid var(--isles-border);
}

.dm-layout {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: var(--isles-space-4);
  align-items: stretch;
  min-height: calc(100vh - 190px);
}

.dm-conversations {
  max-height: calc(100vh - 190px);
}

.dm-chat {
  min-height: calc(100vh - 190px);
}

.dm-back {
  display: none;
}

@media (max-width: 767px) {
  .dm-layout {
    grid-template-columns: minmax(0, 1fr);
    min-height: calc(100vh - 220px);
  }

  .dm-conversations {
    max-height: none;
  }

  .dm-layout--chat-open .dm-conversations {
    display: none;
  }

  .dm-layout:not(.dm-layout--chat-open) .dm-chat {
    display: none;
  }

  .dm-back {
    display: inline-flex;
    gap: 4px;
    align-items: center;
    margin-bottom: var(--isles-space-2);
    padding: 4px 0;
    color: var(--isles-primary);
    font-size: var(--isles-font-sm);
    background: transparent;
    border: none;
    cursor: pointer;
  }
}
</style>
