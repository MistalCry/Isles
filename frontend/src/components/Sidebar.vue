<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useI18n } from 'vue-i18n'
import { SUPPORTED_LOCALES, setLocale, type AppLocale } from '@/i18n'
import { canManageChannelSummary } from '@/utils/channelPermissions'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ChatDotRound,
  Compass,
  HomeFilled,
  HotWater,
  Plus,
  Search,
  ArrowDown,
  Bell,
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useChannelStore } from '@/stores/channel'
import { useNotificationStore } from '@/stores/notifications'
import { useDmStore } from '@/stores/dm'
import { useRequireLogin } from '@/composables/useRequireLogin'
import { APP_NAME, APP_NAME_EN } from '@/constants/app'
import { useSiteStore } from '@/stores/site'
import { useAdminAccess } from '@/composables/useAdminAccess'
import { formatRelativeTime } from '@/utils/format'
import UserAvatar from './UserAvatar.vue'
import ProfileEditDialog from './ProfileEditDialog.vue'
import CreateChannelDialog from './CreateChannelDialog.vue'
import type { Channel } from '@/types/models'

const emit = defineEmits<{ navigate: []; openAnnouncements: [] }>()

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const auth = useAuthStore()
const channelStore = useChannelStore()
const notificationStore = useNotificationStore()
const dmStore = useDmStore()
const site = useSiteStore()
/** 站点名来自平台配置，未加载时回落到内置常量 */
const siteName = computed(() => site.config.name || APP_NAME)
const { requireLogin } = useRequireLogin()
const adminAccess = useAdminAccess()
const { isAdmin } = adminAccess
const { currentUser, isAuthenticated } = storeToRefs(auth)
const { myChannels, loadingMyChannels, currentChannel } = storeToRefs(channelStore)
const { unreadCount } = storeToRefs(notificationStore)
const {
  conversations: dmConversations,
  unreadCount: dmUnreadCount,
  loading: dmLoading,
} = storeToRefs(dmStore)

const createDialogVisible = ref(false)
const myChannelsExpanded = ref(true)
const profileOpen = ref(false)
const messagesExpanded = ref(true)

const recentConversations = computed(() => dmConversations.value.slice(0, 5))

/** 侧边栏「管理」标识：直接进入该频道的管理中心（阻止外层 RouterLink 跳转） */
function openManage(channelId: string): void {
  void router.push({ name: 'manage', params: { channelId } })
}

const signature = computed(() => currentUser.value?.signature || t('nav.noSignature'))
const activeChannelId = computed(() => (route.name === 'channel' ? String(route.params.id) : ''))
const showCurrentChannel = computed(
  () => Boolean(currentChannel.value && !channelStore.isJoined(currentChannel.value.id)),
)

function isActive(name: string): boolean {
  return route.name === name
}

function notifyNavigate(): void {
  emit('navigate')
}

function goTo(name: string): void {
  if (route.name !== name) {
    void router.push({ name })
  }
  notifyNavigate()
}

function openCreateChannel(): void {
  if (!requireLogin(t('nav.loginToCreateChannel'))) return
  createDialogVisible.value = true
}

function handleChannelCreated(channel: Channel): void {
  notifyNavigate()
  void router.push({ name: 'channel', params: { id: channel.id } })
}

async function handleLogout(): Promise<void> {
  await auth.logout()
  channelStore.clear()
  ElMessage.success(t('common.loggedOut'))
  notifyNavigate()
  void router.push('/feed')
}

function goLogin(): void {
  void router.push({ name: 'login' })
  notifyNavigate()
}

function goRegister(): void {
  void router.push({ name: 'register' })
  notifyNavigate()
}

function handleUserCommand(command: string | number | object): void {
  if (command === 'channels') {
    goTo('my-channels')
  } else if (command === 'friends') {
    goTo('friends')
  } else if (command === 'notifications') {
    goTo('notifications')
  } else if (command === 'profile') {
    profileOpen.value = true
  } else if (command === 'announcements') {
    emit('openAnnouncements')
    notifyNavigate()
  } else if (command === 'admin') {
    void router.push('/admin/overview')
    notifyNavigate()
  } else if (command === 'logout') {
    void handleLogout()
  } else if (typeof command === 'string' && command.startsWith('lang-')) {
    const next = command.replace('lang-', '') as AppLocale
    if ((SUPPORTED_LOCALES as readonly string[]).includes(next)) {
      setLocale(next)
      ElMessage.success(next === 'zh-CN' ? t('nav.switchedZh') : 'Switched to English')
    }
  }
}

function goConversation(conversationId: string): void {
  notifyNavigate()
  if (route.name === 'message-detail' && route.params.id === conversationId) return
  void router.push({ name: 'message-detail', params: { id: conversationId } })
}

// 只有「已登录 → 未登录」才清缓存：首帧 isAuthenticated 可能仍是 false（bootstrap 未完成），
// 此时 reset() 会把后台页面刚取到的身份清掉，导致 /admin/* 误判无权限跳回 /feed。
const wasAuthenticated = ref(isAuthenticated.value)

watch(
  () => isAuthenticated.value,
  (loggedIn) => {
    if (loggedIn) {
      void channelStore.fetchMyChannels(true)
      void adminAccess.ensure()
    } else if (wasAuthenticated.value) {
      channelStore.clear()
      adminAccess.reset()
    }
    wasAuthenticated.value = loggedIn
  },
  { immediate: true },
)
</script>

<template>
  <div class="sidebar">
    <div class="sidebar__scroll">
      <RouterLink to="/feed" class="brand" :aria-label="t('nav.home')" @click="notifyNavigate">
        <svg class="brand__mark" viewBox="0 0 64 64" aria-hidden="true">
          <rect width="64" height="64" rx="16" fill="currentColor" />
          <path d="M8 44c8-6 15-6 23-1 9 6 16 6 25 0v13H8z" fill="#fff" opacity="0.22" />
          <path d="M14 34c4-8 10-12 17-12 8 0 14 4 19 12-7 4-13 4-18 1-6-4-12-4-18-1z" fill="#fff" />
          <circle cx="46" cy="18" r="6" fill="#f0a06b" />
        </svg>
        <span class="brand__text">
          <strong>{{ siteName }}</strong>
          <small>{{ APP_NAME_EN }}</small>
        </span>
      </RouterLink>

      <nav class="sidebar__nav" :aria-label="t('search.mainNav')">
        <button
          type="button"
          class="nav-item"
          :class="{ 'is-active': isActive('search') }"
          @click="goTo('search')"
        >
          <el-icon><Search /></el-icon>
          <span>{{ t('common.search') }}</span>
        </button>
        <button
          type="button"
          class="nav-item"
          :class="{ 'is-active': isActive('ai') }"
          @click="goTo('ai')"
        >
          <el-icon><ChatDotRound /></el-icon>
          <span>{{ t('nav.ai') }}</span>
        </button>
        <button
          type="button"
          class="nav-item"
          :class="{ 'is-active': isActive('feed') }"
          @click="goTo('feed')"
        >
          <el-icon><HomeFilled /></el-icon>
          <span>{{ t('nav.feed') }}</span>
        </button>
        <button
          type="button"
          class="nav-item"
          :class="{ 'is-active': isActive('hot') }"
          @click="goTo('hot')"
        >
          <el-icon><HotWater /></el-icon>
          <span>{{ t('nav.hot') }}</span>
        </button>
        <button
          type="button"
          class="nav-item"
          :class="{ 'is-active': isActive('notifications') }"
          @click="goTo('notifications')"
        >
          <el-icon><Bell /></el-icon>
          <span>{{ t('nav.notifications') }}</span>
          <el-badge
            v-if="isAuthenticated && unreadCount > 0"
            :value="unreadCount"
            :max="99"
            class="nav-item__badge"
          />
        </button>
      </nav>

      <section class="sidebar__section">
        <div class="section-head">
          <button type="button" class="section-title" @click="myChannelsExpanded = !myChannelsExpanded">
            <el-icon :size="14" class="section-title__caret" :class="{ 'is-collapsed': !myChannelsExpanded }">
              <ArrowDown />
            </el-icon>{{ t('nav.myChannels') }}</button>
          <button type="button" class="icon-btn" :aria-label="t('manage.nav.createChannel')" @click="openCreateChannel">
            <el-icon :size="16"><Plus /></el-icon>
          </button>
        </div>

        <div v-if="myChannelsExpanded">
          <template v-if="isAuthenticated">
            <p v-if="loadingMyChannels && myChannels.length === 0" class="section-hint">{{ t('common.loading') }}</p>
            <ul v-else-if="myChannels.length > 0" class="channel-list">
              <li v-for="channel in myChannels" :key="channel.id">
                <RouterLink
                  :to="{ name: 'channel', params: { id: channel.id } }"
                  class="channel-item"
                  :class="{ 'is-active': activeChannelId === channel.id }"
                  @click="notifyNavigate"
                >
                  <UserAvatar :src="channel.avatarUrl" :name="channel.name" :size="24" square />
                  <span class="channel-item__name">{{ channel.name }}</span>
                  <button
                    v-if="canManageChannelSummary(channel, currentUser?.id)"
                    type="button"
                    class="channel-item__manage"
                    :title="t('channel.settings.manageCenter')"
                    @click.stop.prevent="openManage(channel.id)"
                  >{{ t('common.manage') }}</button>
                </RouterLink>
              </li>
            </ul>
            <p v-else class="section-hint">{{ t('nav.channelEmpty') }}</p>
            <RouterLink to="/my-channels" class="section-more" @click="notifyNavigate">{{ t('nav.viewAllChannels') }}</RouterLink>
          </template>
          <p v-else class="section-hint">{{ t('nav.channelLoadHint') }}</p>
        </div>
      </section>

      <section v-if="showCurrentChannel && currentChannel" class="sidebar__section">
        <div class="section-head">
          <span class="section-title section-title--static">
            <el-icon :size="14"><Compass /></el-icon>{{ t('nav.currentChannel') }}</span>
        </div>
        <RouterLink
          :to="{ name: 'channel', params: { id: currentChannel.id } }"
          class="channel-item is-active"
          @click="notifyNavigate"
        >
          <UserAvatar :src="currentChannel.avatarUrl" :name="currentChannel.name" :size="24" square />
          <span class="channel-item__name">{{ currentChannel.name }}</span>
        </RouterLink>
      </section>

      <section class="sidebar__section">
        <div class="section-head">
          <button type="button" class="section-title" @click="messagesExpanded = !messagesExpanded">
            <el-icon :size="14" class="section-title__caret" :class="{ 'is-collapsed': !messagesExpanded }">
              <ArrowDown />
            </el-icon>
            {{ t('nav.messages') }}
            <el-badge
              v-if="isAuthenticated && dmUnreadCount > 0"
              :value="dmUnreadCount"
              :max="99"
              class="section-title__badge"
            />
          </button>
        </div>
        <div v-if="messagesExpanded">
          <template v-if="isAuthenticated">
            <p v-if="dmLoading && recentConversations.length === 0" class="section-hint">{{ t('nav.dmLoading') }}</p>
            <ul v-else-if="recentConversations.length > 0" class="channel-list">
              <li v-for="conversation in recentConversations" :key="conversation.id">
                <button type="button" class="channel-item dm-item" @click="goConversation(conversation.id)">
                  <UserAvatar
                    :src="conversation.peer.avatarUrl"
                    :name="conversation.peer.nickname || conversation.peer.username"
                    :size="24"
                  />
                  <span class="channel-item__name">
                    {{ conversation.peer.nickname || conversation.peer.username }}
                  </span>
                  <span v-if="conversation.unreadCount > 0" class="dm-item__unread">
                    {{ conversation.unreadCount > 99 ? '99+' : conversation.unreadCount }}
                  </span>
                  <small v-else-if="conversation.lastMessage" class="dm-item__time">
                    {{ formatRelativeTime(conversation.lastMessage.createdAt) }}
                  </small>
                </button>
              </li>
            </ul>
            <p v-else class="section-hint">{{ t('nav.dmEmpty') }}</p>
            <RouterLink to="/messages" class="section-more" @click="notifyNavigate">{{ t('nav.viewAllDm') }}</RouterLink>
          </template>
          <p v-else class="section-hint">{{ t('nav.dmLoadHint') }}</p>
        </div>
      </section>
    </div>

    <footer class="sidebar__footer">
      <el-dropdown v-if="isAuthenticated && currentUser" trigger="click" @command="handleUserCommand">
        <button type="button" class="user-card">
          <UserAvatar :src="currentUser.avatarUrl" :name="auth.displayName" :size="36" />
          <span class="user-card__meta">
            <strong>{{ auth.displayName }}</strong>
            <small>{{ signature }}</small>
          </span>
          <el-icon :size="14" class="user-card__caret"><ArrowDown /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="channels">{{ t('nav.myChannels') }}</el-dropdown-item>
            <el-dropdown-item command="friends">{{ t('nav.friends') }}</el-dropdown-item>
            <el-dropdown-item command="notifications">{{ t('nav.notificationCenter') }}</el-dropdown-item>
            <el-dropdown-item command="profile">{{ t('nav.profile') }}</el-dropdown-item>
            <el-dropdown-item command="announcements">{{ t('announcement.menu') }}</el-dropdown-item>
            <el-dropdown-item v-if="isAdmin" command="admin">{{ t('admin.layout.enter') }}</el-dropdown-item>
            <el-dropdown-item command="lang-zh-CN">{{ t('nav.languageZh') }}</el-dropdown-item>
            <el-dropdown-item command="lang-en-US">{{ t('nav.languageEn') }}</el-dropdown-item>
            <el-dropdown-item command="logout" divided>{{ t('common.logout') }}</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

      <div v-else class="guest-box">
        <p>{{ t('nav.loginHint') }}</p>
        <div class="guest-box__actions">
          <el-button type="primary" size="small" @click="goLogin">{{ t('common.login') }}</el-button>
          <el-button size="small" @click="goRegister">{{ t('common.register') }}</el-button>
        </div>
      </div>
    </footer>

    <ProfileEditDialog v-model="profileOpen" />

    <CreateChannelDialog v-model="createDialogVisible" @created="handleChannelCreated" />
  </div>
</template>

<style scoped lang="scss">
.sidebar {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  background: var(--isles-sidebar-bg);
}

.sidebar__scroll {
  flex: 1;
  min-height: 0;
  padding: var(--isles-space-4) var(--isles-space-3);
  overflow-y: auto;
}

.brand {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  padding: var(--isles-space-2);
  margin-bottom: var(--isles-space-4);
  border-radius: var(--isles-radius-md);

  &:hover {
    background: var(--isles-hover);
  }
}

.brand__mark {
  width: 36px;
  height: 36px;
  color: var(--isles-primary);
}

.brand__text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;

  strong {
    font-size: var(--isles-font-lg);
    letter-spacing: 0.04em;
  }

  small {
    color: var(--isles-text-muted);
    font-size: 10px;
    letter-spacing: 0.18em;
    text-transform: uppercase;
  }
}

.sidebar__nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav-item {
  display: flex;
  gap: var(--isles-space-3);
  align-items: center;
  width: 100%;
  padding: 10px var(--isles-space-3);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-base);
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-md);
  text-align: left;
  cursor: pointer;
  transition:
    background 0.15s ease,
    color 0.15s ease;

  .el-icon {
    color: var(--isles-text-muted);
  }

  &:hover {
    background: var(--isles-hover);
    color: var(--isles-text);
  }

  &.is-active {
    color: var(--isles-primary);
    font-weight: 600;
    background: var(--isles-primary-soft);

    .el-icon {
      color: var(--isles-primary);
    }
  }
}

.sidebar__section {
  margin-top: var(--isles-space-5);
  padding-top: var(--isles-space-3);
  border-top: 1px solid var(--isles-border);
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--isles-space-2);
}

.section-title {
  display: flex;
  gap: var(--isles-space-1);
  align-items: center;
  padding: var(--isles-space-1) 0;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  background: transparent;
  border: none;
  cursor: pointer;
}

.section-title--static {
  cursor: default;
  color: var(--isles-primary);
}

.section-title__caret {
  transition: transform 0.15s ease;

  &.is-collapsed {
    transform: rotate(-90deg);
  }
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  color: var(--isles-text-muted);
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-sm);
  cursor: pointer;

  &:hover {
    color: var(--isles-primary);
    background: var(--isles-hover);
  }
}

.channel-list {
  display: flex;
  flex-direction: column;
  gap: 1px;
  margin-top: var(--isles-space-2);
}

.channel-item {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  padding: 6px var(--isles-space-2);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
  border-radius: var(--isles-radius-sm);

  &:hover {
    background: var(--isles-hover);
    color: var(--isles-text);
  }

  &.is-active {
    color: var(--isles-primary);
    font-weight: 600;
    background: var(--isles-primary-soft);
  }
}

.channel-item__name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.channel-item__manage {
  flex-shrink: 0;
  padding: 0 6px;
  font-size: 10px;
  line-height: 16px;
  color: var(--isles-primary);
  cursor: pointer;
  background: var(--isles-primary-soft, rgb(64 128 255 / 12%));
  border: none;
  border-radius: var(--isles-radius-sm);

  &:hover {
    filter: brightness(0.96);
  }
}

.nav-item__badge {
  margin-left: auto;

  :deep(.el-badge__content) {
    position: static;
    transform: none;
  }
}

.section-title__badge {
  margin-left: var(--isles-space-1);

  :deep(.el-badge__content) {
    position: static;
    transform: none;
  }
}

.dm-item {
  width: 100%;
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
  background: transparent;
  border: none;
  text-align: left;
  cursor: pointer;
}

.dm-item__unread {
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

.dm-item__time {
  flex: none;
  color: var(--isles-text-muted);
  font-size: 10px;
}

.section-hint {
  padding: var(--isles-space-2);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
  line-height: var(--isles-line-base);
}

.section-more {
  display: inline-block;
  margin: var(--isles-space-1) 0 0 var(--isles-space-2);
  color: var(--isles-primary);
  font-size: var(--isles-font-xs);
  background: transparent;
  border: none;
  cursor: pointer;

  &:hover {
    text-decoration: underline;
  }
}

.messages-placeholder {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-1);
  align-items: flex-start;
  padding: var(--isles-space-2);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.sidebar__footer {
  flex: none;
  padding: var(--isles-space-3);
  border-top: 1px solid var(--isles-border);
}

.user-card {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  width: 100%;
  padding: var(--isles-space-2);
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-md);
  text-align: left;
  cursor: pointer;

  &:hover {
    background: var(--isles-hover);
  }
}

.user-card__meta {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  line-height: 1.3;

  strong {
    overflow: hidden;
    font-size: var(--isles-font-sm);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    overflow: hidden;
    color: var(--isles-text-muted);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.user-card__caret {
  color: var(--isles-text-muted);
}

.guest-box {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
  padding: var(--isles-space-2);

  p {
    color: var(--isles-text-muted);
    font-size: var(--isles-font-xs);
  }
}

.guest-box__actions {
  display: flex;
  gap: var(--isles-space-2);
}
</style>
