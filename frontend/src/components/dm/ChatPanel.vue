<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Picture, Promotion } from '@element-plus/icons-vue'
import { mediaApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { formatRelativeTime } from '@/utils/format'
import type { DmConversation, DmMessage } from '@/types/models'
import type { SendDmMessagePayload } from '@/api/dm'
import UserAvatar from '../UserAvatar.vue'
import MediaViewer from '../MediaViewer.vue'
import { EMOJI_LIST } from '@/constants/app'

const props = withDefaults(
  defineProps<{
    conversation: DmConversation
    messages: DmMessage[]
    loading?: boolean
    error?: string | null
    hasMore?: boolean
    sending?: boolean
  }>(),
  {
    loading: false,
    error: null,
    hasMore: false,
    sending: false,
  },
)

const emit = defineEmits<{
  send: [payload: SendDmMessagePayload]
  'load-older': []
  retry: []
}>()

const auth = useAuthStore()
const draft = ref('')
const uploading = ref(false)
const fileInput = ref<HTMLInputElement>()
const scrollRef = ref<HTMLElement | null>(null)

const currentUserId = computed(() => auth.currentUser?.id ?? '')
const peerName = computed(
  () => props.conversation.peer.nickname || props.conversation.peer.username || '岛民',
)

function isOwn(message: DmMessage): boolean {
  return message.senderId === currentUserId.value
}

function senderName(message: DmMessage): string {
  if (message.sender) return message.sender.nickname || message.sender.username || peerName.value
  return isOwn(message) ? '我' : peerName.value
}

async function scrollToBottom(): Promise<void> {
  await nextTick()
  const element = scrollRef.value
  if (element) element.scrollTop = element.scrollHeight
}

function createClientMessageId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `local_${Date.now()}`
}

function sendText(): void {
  const content = draft.value.trim()
  if (!content) return
  if (props.sending) return
  emit('send', { type: 'TEXT', content, clientMessageId: createClientMessageId() })
  draft.value = ''
}

function appendEmoji(emoji: string): void {
  draft.value += emoji
}

function triggerFilePick(): void {
  fileInput.value?.click()
}

async function handleImagePick(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  const invalid = mediaApi.validateMediaFile(file)
  if (invalid) {
    ElMessage.warning(invalid)
    return
  }
  uploading.value = true
  try {
    const media = await mediaApi.uploadMedia(file)
    emit('send', {
      type: 'IMAGE',
      media: [media],
      clientMessageId: createClientMessageId(),
    })
  } catch {
    // 拦截器已提示
  } finally {
    uploading.value = false
  }
}

watch(
  () => props.messages[props.messages.length - 1]?.id,
  () => {
    void scrollToBottom()
  },
)

watch(
  () => props.conversation.id,
  () => {
    draft.value = ''
    void scrollToBottom()
  },
)

onMounted(() => {
  void scrollToBottom()
})
</script>

<template>
  <section class="chat-panel" aria-label="私信会话">
    <header class="chat-panel__head">
      <UserAvatar :src="conversation.peer.avatarUrl" :name="peerName" :size="36" />
      <div class="chat-panel__peer">
        <strong>{{ peerName }}</strong>
        <small class="muted">@{{ conversation.peer.username }}</small>
      </div>
      <span v-if="conversation.peer.signature" class="muted chat-panel__signature line-clamp-1">
        {{ conversation.peer.signature }}
      </span>
    </header>

    <div ref="scrollRef" class="chat-panel__messages">
      <div v-if="hasMore" class="chat-panel__load-older">
        <el-button text type="primary" size="small" :loading="loading" @click="$emit('load-older')">
          加载更早的消息
        </el-button>
      </div>

      <div v-if="loading && messages.length === 0" class="chat-panel__state muted">正在加载消息…</div>
      <div v-else-if="error && messages.length === 0" class="chat-panel__state">
        <span class="text-danger">{{ error }}</span>
        <el-button text type="primary" size="small" @click="$emit('retry')">重试</el-button>
      </div>
      <div v-else-if="messages.length === 0" class="chat-panel__state muted">
        还没有消息，发一句问候开始聊天吧。
      </div>

      <div
        v-for="message in messages"
        :key="message.id || message.clientMessageId || 'pending-message'"
        class="message-row"
        :class="{ 'message-row--own': isOwn(message) }"
      >
        <UserAvatar
          v-if="!isOwn(message)"
          :src="message.sender?.avatarUrl || conversation.peer.avatarUrl"
          :name="senderName(message)"
          :size="30"
        />
        <div class="message-bubble-wrap">
          <span class="message-meta muted">
            {{ senderName(message) }} · {{ formatRelativeTime(message.createdAt) }}
          </span>
          <div class="message-bubble" :class="{ 'message-bubble--own': isOwn(message) }">
            <template v-if="message.type === 'IMAGE'">
              <MediaViewer :media="message.media" compact />
            </template>
            <template v-else-if="message.type === 'POST' && message.post">
              <RouterLink
                :to="{ name: 'post', params: { id: message.post.postId } }"
                class="shared-post"
              >
                <span class="shared-post__label">分享帖子</span>
                <strong class="shared-post__title line-clamp-1">
                  {{ message.post.title || message.post.summary || '查看帖子' }}
                </strong>
                <span v-if="message.post.channelName" class="muted shared-post__channel">
                  {{ message.post.channelName }}
                </span>
              </RouterLink>
            </template>
            <template v-else-if="message.type === 'SYSTEM'">
              <span class="message-system">{{ message.content }}</span>
            </template>
            <template v-else>
              <span class="message-text">{{ message.content }}</span>
            </template>
          </div>
          <span v-if="isOwn(message)" class="message-status muted">
            {{ message.read ? '已读' : '未读' }}
          </span>
        </div>
      </div>
    </div>

    <footer class="chat-panel__composer">
      <el-input
        v-model="draft"
        type="textarea"
        :rows="2"
        maxlength="2000"
        resize="none"
        placeholder="输入消息，Enter 发送，Shift + Enter 换行"
        @keydown.enter.exact.prevent="sendText"
      />
      <div class="chat-panel__actions">
        <el-popover trigger="click" :width="220" placement="top-start">
          <template #reference>
            <el-button text size="small">😊 emoji</el-button>
          </template>
          <div class="emoji-panel">
            <button v-for="emoji in EMOJI_LIST" :key="emoji" type="button" class="emoji-btn" @click="appendEmoji(emoji)">
              {{ emoji }}
            </button>
          </div>
        </el-popover>
        <el-button text size="small" :icon="Picture" :loading="uploading" @click="triggerFilePick">图片</el-button>
        <el-button
          class="chat-panel__send"
          type="primary"
          size="small"
          :icon="Promotion"
          :loading="sending"
          :disabled="!draft.trim()"
          @click="sendText"
        >
          发送
        </el-button>
      </div>
      <input
        ref="fileInput"
        type="file"
        accept="image/*"
        class="visually-hidden"
        aria-label="选择图片发送"
        @change="handleImagePick"
      />
    </footer>
  </section>
</template>

<style scoped lang="scss">
.chat-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 480px;
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
  overflow: hidden;
}

.chat-panel__head {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  padding: var(--isles-space-3) var(--isles-space-4);
  border-bottom: 1px solid var(--isles-border);
}

.chat-panel__peer {
  display: flex;
  flex-direction: column;
  line-height: 1.3;

  strong {
    font-size: var(--isles-font-sm);
  }

  small {
    font-size: var(--isles-font-xs);
  }
}

.chat-panel__signature {
  margin-left: auto;
  font-size: var(--isles-font-xs);
}

.chat-panel__messages {
  flex: 1;
  min-height: 0;
  padding: var(--isles-space-4);
  overflow-y: auto;
}

.chat-panel__load-older {
  text-align: center;
}

.chat-panel__state {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
  align-items: center;
  justify-content: center;
  padding: var(--isles-space-8);
  font-size: var(--isles-font-sm);
}

.message-row {
  display: flex;
  gap: var(--isles-space-2);
  align-items: flex-start;
  margin-bottom: var(--isles-space-4);

  &--own {
    flex-direction: row-reverse;

    .message-bubble-wrap {
      align-items: flex-end;
    }

    .message-meta {
      text-align: right;
    }
  }
}

.message-bubble-wrap {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-width: 72%;
}

.message-meta {
  font-size: 10px;
}

.message-bubble {
  padding: var(--isles-space-2) var(--isles-space-3);
  background: var(--isles-surface-muted);
  border-radius: 2px var(--isles-radius-md) var(--isles-radius-md) var(--isles-radius-md);
  font-size: var(--isles-font-sm);
  line-height: var(--isles-line-base);

  &--own {
    background: var(--isles-primary-soft);
    border-radius: var(--isles-radius-md) 2px var(--isles-radius-md) var(--isles-radius-md);
  }
}

.message-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.message-status {
  font-size: 10px;
}

.message-system {
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.shared-post {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 180px;
  padding: var(--isles-space-2);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-sm);
}

.shared-post__label {
  color: var(--isles-primary);
  font-size: 10px;
}

.shared-post__title {
  font-size: var(--isles-font-sm);
}

.shared-post__channel {
  font-size: 10px;
}

.chat-panel__composer {
  padding: var(--isles-space-3) var(--isles-space-4);
  border-top: 1px solid var(--isles-border);
}

.chat-panel__actions {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  margin-top: var(--isles-space-2);
}

.chat-panel__time {
  margin-left: auto;
}

.chat-panel__send {
  margin-left: auto;
}

.emoji-panel {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: var(--isles-space-1);
}

.emoji-btn {
  padding: var(--isles-space-1);
  font-size: var(--isles-font-md);
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-sm);
  cursor: pointer;

  &:hover {
    background: var(--isles-hover);
  }
}
</style>
