<script setup lang="ts">
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
    name: conversation.peer.nickname || conversation.peer.username || '岛民',
    avatarUrl: conversation.peer.avatarUrl,
  }
}

function targetForFriend(friend: FriendItem): Target {
  return {
    key: `friend:${friend.userId}`,
    kind: 'friend',
    userId: friend.userId,
    name: friend.nickname || friend.username || '岛民',
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
    error.value = resolveErrorMessage(err, '私信会话加载失败')
  } finally {
    loading.value = false
  }
}

async function copyLink(): Promise<void> {
  const copied = await copyText(absoluteUrl(`/posts/${props.post.id}`))
  if (copied) {
    ElMessage.success('帖子链接已复制')
  } else {
    ElMessage.warning('复制失败，请手动复制地址栏链接')
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
    ElMessage.warning('请选择要分享的好友或会话')
    return
  }
  if (!auth.isAuthenticated) {
    ElMessage.warning('请先登录')
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
      throw new Error('无法创建私信会话')
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
    ElMessage.success(`已分享给 ${target.name}`)
    emit('sent')
    visible.value = false
  } catch (err) {
    ElMessage.error(resolveErrorMessage(err, '分享失败，请稍后重试'))
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
  <el-dialog v-model="visible" title="分享帖子" width="min(520px, 94vw)" append-to-body>
    <div class="share-post isles-card">
      <strong class="line-clamp-1">{{ post.title || post.content.slice(0, 40) }}</strong>
      <p class="muted line-clamp-2">{{ post.content.slice(0, 100) }}</p>
      <el-button :icon="CopyDocument" size="small" @click="copyLink">复制链接</el-button>
    </div>

    <div class="share-search">
      <el-input v-model="keyword" :prefix-icon="Search" placeholder="搜索好友或会话" clearable />
    </div>

    <LoadingState v-if="loading" :rows="2" />

    <div v-else class="share-lists">
      <section v-if="filteredConversations.length > 0" class="share-group">
        <h4>最近会话</h4>
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
          <span v-if="selectedKey === targetForConversation(conversation).key" class="share-target__check">已选择</span>
        </button>
      </section>

      <section v-if="filteredFriends.length > 0" class="share-group">
        <h4>好友</h4>
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
          <span v-if="selectedKey === targetForFriend(friend).key" class="share-target__check">已选择</span>
        </button>
      </section>

      <EmptyState
        v-if="filteredConversations.length === 0 && filteredFriends.length === 0"
        compact
        title="没有可分享的对象"
        description="添加好友或先发起私信，也可以直接复制帖子链接。"
      />
    </div>

    <p v-if="error" class="text-danger share-error">{{ error }}</p>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :icon="Promotion" :loading="sending" :disabled="!selectedKey" @click="send">
        发送
      </el-button>
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
