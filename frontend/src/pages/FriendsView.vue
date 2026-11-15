<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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

const { t } = useI18n()

const router = useRouter()
const route = useRoute()
const TAB_KEYS = ['friends', 'incoming', 'outgoing', 'blocked'] as const
const initialTab = String(route.query.tab ?? '')
const activeTab = ref(TAB_KEYS.includes(initialTab as (typeof TAB_KEYS)[number]) ? initialTab : 'friends')
const friends = ref<FriendItem[]>([])
const incoming = ref<FriendRequestItem[]>([])
const outgoing = ref<FriendRequestItem[]>([])
const blocked = ref<FriendItem[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const actionId = ref('')

const tabs = [
  { key: 'friends', label: t('dm.share.friends') },
  { key: 'incoming', label: t('friends.tabIncoming') },
  { key: 'outgoing', label: t('friends.tabOutgoing') },
  { key: 'blocked', label: t('friends.tabBlocked') },
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
  return peer?.nickname || peer?.username || t('common.islander')
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
    error.value = resolveErrorMessage(err, t('friends.loadFailed'))
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
    await ElMessageBox.confirm(
      t('friends.removeConfirm', { name: friend.nickname || friend.username }),
      t('friends.remove'),
      {
      type: 'warning',
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  try {
    await friendsApi.removeFriend(friend.userId)
    friends.value = friends.value.filter((item) => item.userId !== friend.userId)
    ElMessage.success(t('friends.removed'))
  } catch {
    // 拦截器已提示
  }
}

async function blockFriend(friend: FriendItem): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('friends.blockConfirm', { name: friend.nickname || friend.username }),
      t('friends.blockTitle'),
      {
      type: 'warning',
      confirmButtonText: t('friends.block'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  try {
    await friendsApi.blockUser(friend.userId)
    friends.value = friends.value.filter((item) => item.userId !== friend.userId)
    ElMessage.success(t('friends.blocked'))
    await loadAll()
  } catch {
    // 拦截器已提示
  }
}

async function unblock(friend: FriendItem): Promise<void> {
  try {
    await friendsApi.unblockUser(friend.userId)
    blocked.value = blocked.value.filter((item) => item.userId !== friend.userId)
    ElMessage.success(t('friends.unblocked'))
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
      ElMessage.success(t('friends.accepted'))
    } else {
      await friendsApi.rejectFriendRequest(request.id)
      ElMessage.success(t('friends.rejected'))
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
        <h1 class="page-title">{{ t('dm.share.friends') }}</h1>
        <p class="page-subtitle">{{ t('friends.subtitle') }}</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadAll">{{ t('common.refresh') }}</el-button>
    </header>

    <el-tabs v-model="activeTab">
      <el-tab-pane v-for="tab in tabs" :key="tab.key" :label="tab.label" :name="tab.key" />
    </el-tabs>

    <LoadingState v-if="loading && currentList.length === 0" :rows="4" :tip="t('friends.loading')" />
    <ErrorState v-else-if="error && currentList.length === 0" :message="error" @retry="loadAll" />
    <EmptyState
      v-else-if="currentList.length === 0"
      :title="activeTab === 'friends' ? t('friends.emptyFriends') : activeTab === 'blocked' ? t('friends.emptyBlocked') : t('friends.emptyRequests')"
      :description="t('friends.emptyHint')"
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
          <small v-if="item.since" class="muted">{{ t('friends.since', { time: formatRelativeTime(item.since) }) }}</small>
        </div>
        <div class="friend-card__actions">
          <template v-if="activeTab === 'friends'">
            <el-button type="primary" size="small" :icon="ChatDotRound" @click="startChat(item)">{{ t('friends.dm') }}</el-button>
            <el-button size="small" @click="removeFriend(item)">{{ t('friends.remove') }}</el-button>
            <el-button size="small" type="danger" plain @click="blockFriend(item)">{{ t('friends.block') }}</el-button>
          </template>
          <el-button v-else size="small" @click="unblock(item)">{{ t('friends.unblock') }}</el-button>
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
          <small v-if="item.message" class="muted">{{ t('friends.message', { text: item.message }) }}</small>
        </div>
        <div class="friend-card__actions">
          <template v-if="activeTab === 'incoming'">
            <el-button
              type="primary"
              size="small"
              :loading="actionId === item.id"
              @click="handleRequest(item, 'accept')"
            >
              {{ t('friends.accept') }}
            </el-button>
            <el-button size="small" :disabled="actionId === item.id" @click="handleRequest(item, 'reject')">{{ t('channel.invite.reject') }}</el-button>
          </template>
          <el-tag v-else size="small" effect="plain">{{ t('friends.waiting') }}</el-tag>
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
