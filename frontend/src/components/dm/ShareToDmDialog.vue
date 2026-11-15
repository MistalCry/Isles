<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument, Promotion, Search } from '@element-plus/icons-vue'
import { friendsApi, postsApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { useDmStore } from '@/stores/dm'
import { useAuthStore } from '@/stores/auth'
import { absoluteUrl } from '@/utils/format'
import { copyText } from '@/utils/clipboard'
import type { DmConversation, FriendItem, Post } from '@/types/models'
import UserAvatar from '../UserAvatar.vue'
import LoadingState from '../LoadingState.vue'
import EmptyState from '../EmptyState.vue'

const { t } = useI18n()

const props = defineProps<{
  modelValue: boolean
  post: Post
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  sent: []
}>()

const dm = useDmStore()
const auth = useAuthStore()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const loading = ref(false)
const error = ref<string | null>(null)
const sending = ref(false)
const keyword = ref('')
const conversations = ref<DmConversation[]>([])
const friends = ref<FriendItem[]>([])
const selectedKey = ref('')

interface Target {
  key: string
  kind: 'conversation' | 'friend'
  conversationId?: string
  userId?: string
  name: string
  avatarUrl?: string | null
}

const filteredConversations = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  return conversations.value.filter((conversation) => {
    if (!key) return true
    return (conversation.peer.nickname || conversation.peer.username || '').toLowerCase().includes(key)
  })
})

const filteredFriends = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  return friends.value.filter((friend) => {
    if (!key) return true
    return (friend.nickname || friend.username || '').toLowerCase().includes(key)
  })
})

function targetForConversation(conversation: DmConversation): Target {
  return {
    key: `conversation:${conversation.id}`,
    kind: 'conversation',
    conversationId: conversation.id,
    name: conversation.peer.nickname || conversation.peer.username || t('common.islander'),
    avatarUrl: conversation.peer.avatarUrl,
  }
}

function targetForFriend(friend: FriendItem): Target {
  return {
    key: `friend:${friend.userId}`,
    kind: 'friend',
    userId: friend.userId,
    name: friend.nickname || friend.username || t('common.islander'),
    avatarUrl: friend.avatarUrl,
  }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    await dm.fetchConversations(true)
    friends.value = await friendsApi.getFriends().catch(() => [])
    conversations.value = [...dm.conversations]
  } catch (err) {
    error.value = resolveErrorMessage(err, t('dm.share.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function copyLink(): Promise<void> {
  const copied = await copyText(absoluteUrl(`/posts/${props.post.id}`))
  if (copied) {
    ElMessage.success(t('dm.share.copied'))
  } else {
    ElMessage.warning(t('dm.share.copyFailed'))
  }
  try {
    await postsApi.countPostShare(props.post.id)
  } catch {
    // 分享计数失败不打断
  }
}

async function send(): Promise<void> {
  const target = [...filteredConversations.value.map(targetForConversation), ...filteredFriends.value.map(targetForFriend)].find(
    (item) => item.key === selectedKey.value,
  )
  if (!target) {
    ElMessage.warning(t('dm.share.pickTarget'))
    return
  }
  if (!auth.isAuthenticated) {
    ElMessage.warning(t('dm.share.loginRequired'))
    return
  }
  sending.value = true
  try {
    let conversationId = target.conversationId
    if (!conversationId && target.userId) {
      const conversation = await dm.openConversationWithUser(target.userId)
      conversationId = conversation.id
    }
    if (!conversationId) {
      throw new Error(t('dm.share.createFailed'))
    }
    await dm.sendMessage(conversationId, {
      type: 'POST',
      postId: props.post.id,
      content: props.post.title || undefined,
    })
    try {
      await postsApi.countPostShare(props.post.id)
    } catch {
      // 分享计数失败不打断
    }
    ElMessage.success(t('dm.share.sentTo', { name: target.name }))
    emit('sent')
    visible.value = false
  } catch (err) {
    ElMessage.error(resolveErrorMessage(err, t('dm.share.failed')))
  } finally {
    sending.value = false
  }
}

watch(visible, (open) => {
  if (!open) return
  selectedKey.value = ''
  keyword.value = ''
  error.value = null
  void load()
})
</script>

<template>
  <el-dialog v-model="visible" :title="t('dm.chat.sharePost')" width="min(520px, 94vw)" append-to-body>
    <div class="share-post isles-card">
      <strong class="line-clamp-1">{{ post.title || post.content.slice(0, 40) }}</strong>
      <p class="muted line-clamp-2">{{ post.content.slice(0, 100) }}</p>
      <el-button :icon="CopyDocument" size="small" @click="copyLink">{{ t('dm.share.copyLink') }}</el-button>
    </div>

    <div class="share-search">
      <el-input v-model="keyword" :prefix-icon="Search" :placeholder="t('dm.searchFriend')" clearable />
    </div>

    <LoadingState v-if="loading" :rows="2" />

    <div v-else class="share-lists">
      <section v-if="filteredConversations.length > 0" class="share-group">
        <h4>{{ t('dm.share.recent') }}</h4>
        <button
          v-for="conversation in filteredConversations"
          :key="conversation.id"
          type="button"
          class="share-target"
          :class="{ 'is-selected': selectedKey === targetForConversation(conversation).key }"
          @click="selectedKey = targetForConversation(conversation).key"
        >
          <UserAvatar
            :src="conversation.peer.avatarUrl"
            :name="conversation.peer.nickname || conversation.peer.username"
            :size="30"
          />
          <span>{{ conversation.peer.nickname || conversation.peer.username }}</span>
          <span v-if="selectedKey === targetForConversation(conversation).key" class="share-target__check">{{ t('dm.share.selected') }}</span>
        </button>
      </section>

      <section v-if="filteredFriends.length > 0" class="share-group">
        <h4>{{ t('dm.share.friends') }}</h4>
        <button
          v-for="friend in filteredFriends"
          :key="friend.userId"
          type="button"
          class="share-target"
          :class="{ 'is-selected': selectedKey === targetForFriend(friend).key }"
          @click="selectedKey = targetForFriend(friend).key"
        >
          <UserAvatar :src="friend.avatarUrl" :name="friend.nickname || friend.username" :size="30" />
          <span>{{ friend.nickname || friend.username }}</span>
          <span v-if="selectedKey === targetForFriend(friend).key" class="share-target__check">{{ t('dm.share.selected') }}</span>
        </button>
      </section>

      <EmptyState
        v-if="filteredConversations.length === 0 && filteredFriends.length === 0"
        compact
        :title="t('dm.noTargets')"
        :description="t('dm.noTargetsHint')"
      />
    </div>

    <p v-if="error" class="text-danger share-error">{{ error }}</p>

    <template #footer>
      <el-button @click="visible = false">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :icon="Promotion" :loading="sending" :disabled="!selectedKey" @click="send">{{ t('common.send') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.share-post {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-1);
  align-items: flex-start;
  padding: var(--isles-space-3);
  margin-bottom: var(--isles-space-3);

  p {
    font-size: var(--isles-font-xs);
  }
}

.share-search {
  margin-bottom: var(--isles-space-2);
}

.share-lists {
  max-height: 320px;
  overflow-y: auto;
}

.share-group {
  margin-bottom: var(--isles-space-2);

  h4 {
    margin-bottom: var(--isles-space-1);
    color: var(--isles-text-muted);
    font-size: var(--isles-font-xs);
    font-weight: 600;
  }
}

.share-target {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  width: 100%;
  padding: var(--isles-space-2);
  font-size: var(--isles-font-sm);
  text-align: left;
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-sm);
  cursor: pointer;

  &:hover {
    background: var(--isles-hover);
  }

  &.is-selected {
    background: var(--isles-primary-soft);
  }
}

.share-target__check {
  margin-left: auto;
  color: var(--isles-primary);
  font-size: var(--isles-font-xs);
}

.share-error {
  margin-top: var(--isles-space-2);
  font-size: var(--isles-font-xs);
}
</style>
