<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, Refresh } from '@element-plus/icons-vue'
import { dmApi, friendsApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { formatRelativeTime } from '@/utils/format'
import type { FriendItem, FriendRequestItem } from '@/types/models'
import UserAvatar from '@/components/UserAvatar.vue'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const router = useRouter()
const activeTab = ref('friends')
const friends = ref<FriendItem[]>([])
const incoming = ref<FriendRequestItem[]>([])
const outgoing = ref<FriendRequestItem[]>([])
const blocked = ref<FriendItem[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const actionId = ref('')

const tabs = [
  { key: 'friends', label: '好友' },
  { key: 'incoming', label: '收到的申请' },
  { key: 'outgoing', label: '发出的申请' },
  { key: 'blocked', label: '黑名单' },
]

const currentList = computed(() => {
  if (activeTab.value === 'friends') return friends.value
  if (activeTab.value === 'incoming') return incoming.value
  if (activeTab.value === 'outgoing') return outgoing.value
  return blocked.value
})

const friendCards = computed<FriendItem[]>(() => {
  if (activeTab.value === 'friends') return friends.value
  if (activeTab.value === 'blocked') return blocked.value
  return []
})

const requestCards = computed<FriendRequestItem[]>(() => {
  if (activeTab.value === 'incoming') return incoming.value
  if (activeTab.value === 'outgoing') return outgoing.value
  return []
})

function requestPeer(request: FriendRequestItem) {
  return activeTab.value === 'outgoing' ? request.toUser : request.fromUser
}

function requestName(request: FriendRequestItem): string {
  const peer = requestPeer(request)
  return peer?.nickname || peer?.username || '岛民'
}

async function loadAll(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const [friendList, incomingList, outgoingList, blockedList] = await Promise.all([
      friendsApi.getFriends(),
      friendsApi.getFriendRequests('incoming'),
      friendsApi.getFriendRequests('outgoing'),
      friendsApi.getBlockedUsers(),
    ])
    friends.value = friendList
    incoming.value = incomingList
    outgoing.value = outgoingList
    blocked.value = blockedList
  } catch (err) {
    error.value = resolveErrorMessage(err, '好友数据加载失败')
  } finally {
    loading.value = false
  }
}

async function startChat(friend: FriendItem): Promise<void> {
  try {
    const conversation = await dmApi.createConversation(friend.userId)
    await router.push({ name: 'message-detail', params: { id: conversation.id } })
  } catch {
    // 拦截器已提示
  }
}

async function removeFriend(friend: FriendItem): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除好友「${friend.nickname || friend.username}」？`, '删除好友', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await friendsApi.removeFriend(friend.userId)
    friends.value = friends.value.filter((item) => item.userId !== friend.userId)
    ElMessage.success('好友已删除')
  } catch {
    // 拦截器已提示
  }
}

async function blockFriend(friend: FriendItem): Promise<void> {
  try {
    await ElMessageBox.confirm(`拉黑后对方将无法向你发起好友申请或私信。确认拉黑「${friend.nickname || friend.username}」？`, '拉黑用户', {
      type: 'warning',
      confirmButtonText: '拉黑',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await friendsApi.blockUser(friend.userId)
    friends.value = friends.value.filter((item) => item.userId !== friend.userId)
    ElMessage.success('已拉黑该用户')
    await loadAll()
  } catch {
    // 拦截器已提示
  }
}

async function unblock(friend: FriendItem): Promise<void> {
  try {
    await friendsApi.unblockUser(friend.userId)
    blocked.value = blocked.value.filter((item) => item.userId !== friend.userId)
    ElMessage.success('已解除拉黑')
  } catch {
    // 拦截器已提示
  }
}

async function handleRequest(request: FriendRequestItem, action: 'accept' | 'reject'): Promise<void> {
  if (actionId.value) return
  actionId.value = request.id
  try {
    if (action === 'accept') {
      await friendsApi.acceptFriendRequest(request.id)
      ElMessage.success('已同意好友申请')
    } else {
      await friendsApi.rejectFriendRequest(request.id)
      ElMessage.success('已拒绝好友申请')
    }
    incoming.value = incoming.value.filter((item) => item.id !== request.id)
    await loadAll()
  } catch {
    // 拦截器已提示
  } finally {
    actionId.value = ''
  }
}

onMounted(() => {
  void loadAll()
})
</script>

<template>
  <div class="page friends-page">
    <header class="page-header">
      <div>
        <h1 class="page-title">好友</h1>
        <p class="page-subtitle">双向好友、申请与黑名单管理</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
    </header>

    <el-tabs v-model="activeTab">
      <el-tab-pane v-for="tab in tabs" :key="tab.key" :label="tab.label" :name="tab.key" />
    </el-tabs>

    <LoadingState v-if="loading && currentList.length === 0" :rows="4" tip="正在加载好友数据…" />
    <ErrorState v-else-if="error && currentList.length === 0" :message="error" @retry="loadAll" />
    <EmptyState
      v-else-if="currentList.length === 0"
      :title="activeTab === 'friends' ? '还没有好友' : activeTab === 'blocked' ? '黑名单为空' : '没有相关申请'"
      description="在用户卡片中点击「加好友」即可发送好友申请。"
    />

    <div v-else class="friends-list">
      <article
        v-for="item in friendCards"
        :key="item.userId"
        class="friend-card isles-card"
      >
        <UserAvatar :src="item.avatarUrl" :name="item.nickname || item.username" :size="42" />
        <div class="friend-card__meta">
          <strong>{{ item.nickname || item.username }}</strong>
          <small class="muted">@{{ item.username }}<template v-if="item.signature"> · {{ item.signature }}</template></small>
          <small v-if="item.since" class="muted">成为好友于 {{ formatRelativeTime(item.since) }}</small>
        </div>
        <div class="friend-card__actions">
          <template v-if="activeTab === 'friends'">
            <el-button type="primary" size="small" :icon="ChatDotRound" @click="startChat(item)">私信</el-button>
            <el-button size="small" @click="removeFriend(item)">删除好友</el-button>
            <el-button size="small" type="danger" plain @click="blockFriend(item)">拉黑</el-button>
          </template>
          <el-button v-else size="small" @click="unblock(item)">解除拉黑</el-button>
        </div>
      </article>

      <article
        v-for="item in requestCards"
        :key="item.id"
        class="friend-card isles-card"
      >
        <UserAvatar
          :src="requestPeer(item)?.avatarUrl"
          :name="requestName(item)"
          :size="42"
        />
        <div class="friend-card__meta">
          <strong>{{ requestName(item) }}</strong>
          <small class="muted">@{{ requestPeer(item)?.username || '' }} · {{ formatRelativeTime(item.createdAt) }}</small>
          <small v-if="item.message" class="muted">留言：{{ item.message }}</small>
        </div>
        <div class="friend-card__actions">
          <template v-if="activeTab === 'incoming'">
            <el-button
              type="primary"
              size="small"
              :loading="actionId === item.id"
              @click="handleRequest(item, 'accept')"
            >
              同意
            </el-button>
            <el-button size="small" :disabled="actionId === item.id" @click="handleRequest(item, 'reject')">
              拒绝
            </el-button>
          </template>
          <el-tag v-else size="small" effect="plain">等待对方处理</el-tag>
        </div>
      </article>
    </div>
  </div>
</template>

<style scoped lang="scss">
.friends-list {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
}

.friend-card {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-3);
  align-items: center;
  padding: var(--isles-space-4);
}

.friend-card__meta {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  line-height: 1.4;

  strong {
    font-size: var(--isles-font-sm);
  }

  small {
    font-size: var(--isles-font-xs);
  }
}

.friend-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-1);
}
</style>
