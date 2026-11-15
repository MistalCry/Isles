<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Bell, ChatDotRound, Star, View } from '@element-plus/icons-vue'
import { channelsApi } from '@/api'
import { useNotificationStore } from '@/stores/notifications'
import { formatDateTime, formatRelativeTime } from '@/utils/format'
import type { NotificationItem } from '@/types/models'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'
import UserAvatar from '@/components/UserAvatar.vue'

const { t } = useI18n()

const route = useRoute()
const router = useRouter()
const store = useNotificationStore()
const { items, loading, error, unreadCount, hasMore } = storeToRefs(store)

const channelId = computed(() => String(route.query.channelId ?? '').trim())
const channelName = ref('')
const typeFilter = ref('')
const unreadOnly = ref(false)

const typeOptions = [
  { value: '', label: t('notifications.allTypes') },
  { value: 'POST_LIKED', label: t('notifications.like') },
  { value: 'POST_COMMENTED', label: t('notifications.postComment') },
  { value: 'COMMENT_REPLIED', label: t('notifications.reply') },
  { value: 'SYSTEM', label: t('notifications.system') },
]

function currentQuery() {
  return {
    channelId: channelId.value || undefined,
    type: typeFilter.value || undefined,
    unreadOnly: unreadOnly.value || undefined,
    limit: 20,
  }
}

async function load(): Promise<void> {
  await store.fetchNotifications(currentQuery(), true)
}

async function loadChannelName(): Promise<void> {
  if (!channelId.value) {
    channelName.value = ''
    return
  }
  try {
    const channel = await channelsApi.getChannel(channelId.value)
    channelName.value = channel.name
  } catch {
    channelName.value = ''
  }
}

function clearChannelFilter(): void {
  void router.replace({ name: 'notifications' })
}

/** 好友类通知（申请/通过/拒绝）没有 postId/channelId，按标题判断后跳好友页对应标签 */
function friendTabOf(item: NotificationItem): string | null {
  const title = item.title ?? ''
  if (title.includes(t('notifications.friendRequest')) && !title.includes(t('channel.invite.approve')) && !title.includes(t('channel.invite.reject'))) return 'incoming'
  if (title.includes(t('channel.invite.approve'))) return 'friends'
  if (title.includes(t('channel.invite.reject'))) return 'outgoing'
  return null
}

async function handleItemClick(item: NotificationItem): Promise<void> {
  await store.markRead(item.id)
  if (item.postId) {
    void router.push({
      name: 'post',
      params: { id: item.postId },
      query: item.commentId ? { commentId: item.commentId } : undefined,
    })
    return
  }
  const friendTab = friendTabOf(item)
  if (friendTab) {
    void router.push({ name: 'friends', query: { tab: friendTab } })
    return
  }
  if (item.channelId) {
    void router.push({ name: 'channel', params: { id: item.channelId } })
  }
}

async function markAll(): Promise<void> {
  await store.markAllRead()
  ElMessage.success(t('notifications.allRead'))
}

function notificationIcon(type: string) {
  if (type === 'POST_LIKED') return Star
  if (type === 'POST_COMMENTED' || type === 'COMMENT_REPLIED') return ChatDotRound
  return Bell
}

function notificationTitle(item: NotificationItem): string {
  if (item.title) return item.title
  switch (item.type) {
    case 'POST_LIKED':
      return t('notifications.likeTitle')
    case 'POST_COMMENTED':
      return t('notifications.commentTitle')
    case 'COMMENT_REPLIED':
      return t('notifications.replyTitle')
    default:
      return t('notifications.systemTitle')
  }
}

watch([channelId, typeFilter, unreadOnly], () => {
  void load()
})

watch(
  channelId,
  () => {
    void loadChannelName()
  },
  { immediate: true },
)

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page notifications-page">
    <header class="page-header">
      <div>
        <h1 class="page-title">{{ t('nav.notifications') }}<el-badge v-if="unreadCount > 0" :value="unreadCount" :max="99" class="notifications-badge" />
        </h1>
        <p class="page-subtitle">{{ t('notifications.subtitle') }}</p>
      </div>
      <div class="notifications-actions">
        <el-switch v-model="unreadOnly" :active-text="t('dm.page.unreadOnly')" />
        <el-button :disabled="unreadCount === 0" @click="markAll">{{ t('notifications.markAll') }}</el-button>
      </div>
    </header>

    <div class="notifications-filters">
      <el-select v-model="typeFilter" class="notifications-filters__select">
        <el-option v-for="option in typeOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <div v-if="channelId" class="notifications-channel-filter">
        <el-tag closable effect="plain" @close="clearChannelFilter">
          {{ t('notifications.channelOnly', { name: channelName || channelId }) }}
        </el-tag>
      </div>
      <el-button text type="primary" @click="load">{{ t('common.refresh') }}</el-button>
    </div>

    <LoadingState v-if="loading && items.length === 0" :rows="4" :tip="t('notifications.loading')" />
    <ErrorState v-else-if="error && items.length === 0" :message="error" @retry="load" />
    <EmptyState
      v-else-if="items.length === 0"
      :title="t('notifications.empty')"
      :description="t('notifications.emptyHint')"
    />

    <div v-else class="notification-list">
      <button
        v-for="item in items"
        :key="item.id"
        type="button"
        class="notification-item"
        :class="{ 'is-unread': !item.read }"
        @click="handleItemClick(item)"
      >
        <UserAvatar
          v-if="item.actorNickname"
          :name="item.actorNickname"
          :src="null"
          :size="38"
        />
        <span v-else class="notification-item__icon">
          <el-icon :size="18"><component :is="notificationIcon(item.type)" /></el-icon>
        </span>

        <span class="notification-item__body">
          <span class="notification-item__head">
            <strong>{{ notificationTitle(item) }}</strong>
            <time class="muted" :datetime="item.createdAt">{{ formatRelativeTime(item.createdAt) }}</time>
          </span>
          <span v-if="item.content" class="notification-item__content">{{ item.content }}</span>
          <span class="muted notification-item__meta">
            {{ item.actorNickname ? `${item.actorNickname} · ` : '' }}{{ formatDateTime(item.createdAt) }}
            <span v-if="!item.read" class="notification-item__dot">{{ t('dm.chat.unread') }}</span>
          </span>
        </span>
        <el-icon class="notification-item__arrow" :size="16"><View /></el-icon>
      </button>

      <div class="notifications-more">
        <el-button v-if="hasMore" text type="primary" :loading="loading" @click="store.loadMore()">
          {{ t('notifications.loadMore') }}
        </el-button>
        <span v-else class="muted">{{ t('hot.end') }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.notifications-badge {
  margin-left: var(--isles-space-2);

  :deep(.el-badge__content) {
    position: static;
    transform: none;
  }
}

.notifications-actions {
  display: flex;
  gap: var(--isles-space-3);
  align-items: center;
}

.notifications-filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-3);
  align-items: center;
  margin-bottom: var(--isles-space-3);
}

.notifications-filters__select {
  width: 150px;
}

.notifications-channel-filter {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
}

.notification-list {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
}

.notification-item {
  display: flex;
  gap: var(--isles-space-3);
  align-items: flex-start;
  width: 100%;
  padding: var(--isles-space-4);
  text-align: left;
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-md);
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    background 0.15s ease;

  &:hover {
    border-color: var(--isles-border-strong);
    background: var(--isles-surface-muted);
  }

  &.is-unread {
    background: var(--isles-primary-soft);
    border-color: var(--isles-primary-soft);
  }
}

.notification-item__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  color: var(--isles-primary);
  background: var(--isles-surface);
  border-radius: 50%;
}

.notification-item__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.notification-item__head {
  display: flex;
  gap: var(--isles-space-3);
  align-items: baseline;
  justify-content: space-between;

  strong {
    font-size: var(--isles-font-sm);
  }

  time {
    flex: none;
    font-size: var(--isles-font-xs);
  }
}

.notification-item__content {
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
  line-height: var(--isles-line-base);
  white-space: pre-wrap;
}

.notification-item__meta {
  font-size: var(--isles-font-xs);
}

.notification-item__dot {
  padding: 0 6px;
  margin-left: var(--isles-space-1);
  color: #fff;
  background: var(--isles-danger);
  border-radius: var(--isles-radius-pill);
}

.notification-item__arrow {
  align-self: center;
  color: var(--isles-text-muted);
}

.notifications-more {
  display: flex;
  justify-content: center;
  padding: var(--isles-space-4);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}
</style>
