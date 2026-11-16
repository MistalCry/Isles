<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, ref, type Component } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowLeft,
  DataAnalysis,
  Grid,
  Calendar,
  Document,
  Files,
  HomeFilled,
  MagicStick,
  MessageBox,
  Plus,
  Setting,
  User,
  Warning,
} from '@element-plus/icons-vue'
import { useChannelStore } from '@/stores/channel'
import { useRequireLogin } from '@/composables/useRequireLogin'
import {
  CHANNEL_PERMISSIONS,
  hasChannelPermission,
} from '@/utils/channelPermissions'
import { formatCount } from '@/utils/format'
import type { ChannelSummary } from '@/types/models'
import CreateChannelDialog from '../CreateChannelDialog.vue'
import UserAvatar from '../UserAvatar.vue'

const { t } = useI18n()

const props = defineProps<{
  channelId: string
  channelName?: string
  activeSection: string
  permissions: Set<string>
  manageableChannels: ChannelSummary[]
}>()

const emit = defineEmits<{
  'switch-channel': [channelId: string]
}>()

const router = useRouter()
const channelStore = useChannelStore()
const { requireLogin } = useRequireLogin()

const createDialogVisible = ref(false)

interface NavItem {
  key: string
  label: string
  icon: Component
  allowed: boolean
}

const navItems = computed<NavItem[]>(() => [
  { key: 'home', label: t('manage.nav.overview'), icon: HomeFilled, allowed: true },
  {
    key: 'profile',
    label: t('channel.settings.tabProfile'),
    icon: Setting,
    allowed: hasChannelPermission(props.permissions, CHANNEL_PERMISSIONS.CHANNEL_SETTINGS),
  },
  {
    key: 'content',
    label: t('manage.nav.content'),
    icon: Document,
    allowed:
      hasChannelPermission(props.permissions, CHANNEL_PERMISSIONS.CONTENT_MANAGE) ||
      hasChannelPermission(props.permissions, CHANNEL_PERMISSIONS.POST_DELETE) ||
      hasChannelPermission(props.permissions, CHANNEL_PERMISSIONS.POST_PIN) ||
      hasChannelPermission(props.permissions, CHANNEL_PERMISSIONS.POST_ESSENCE),
  },
  {
    key: 'members',
    label: t('manage.nav.members'),
    icon: User,
    allowed:
      hasChannelPermission(props.permissions, CHANNEL_PERMISSIONS.MEMBER_REMOVE) ||
      hasChannelPermission(props.permissions, CHANNEL_PERMISSIONS.MEMBER_MUTE) ||
      hasChannelPermission(props.permissions, CHANNEL_PERMISSIONS.ROLE_MANAGE) ||
      hasChannelPermission(props.permissions, CHANNEL_PERMISSIONS.INVITE_APPROVE),
  },
  {
    key: 'boards',
    label: t('manage.nav.boards'),
    icon: Files,
    allowed: hasChannelPermission(props.permissions, CHANNEL_PERMISSIONS.BOARD_MANAGE),
  },
  {
    key: 'data',
    label: t('manage.nav.data'),
    icon: DataAnalysis,
    allowed: hasChannelPermission(props.permissions, CHANNEL_PERMISSIONS.DATA_VIEW),
  },
  {
    key: 'logs',
    label: t('manage.nav.logs'),
    icon: MessageBox,
    allowed: hasChannelPermission(props.permissions, CHANNEL_PERMISSIONS.LOG_VIEW),
  },
  {
    key: 'reports',
    label: t('manage.nav.reports'),
    icon: Warning,
    allowed: hasChannelPermission(props.permissions, CHANNEL_PERMISSIONS.CONTENT_MANAGE),
  },
  {
    key: 'apps',
    label: t('manage.nav.apps'),
    icon: Grid,
    allowed: hasChannelPermission(props.permissions, CHANNEL_PERMISSIONS.CHANNEL_SETTINGS),
  },
  {
    key: 'activities',
    label: t('manage.nav.activities'),
    icon: Calendar,
    allowed: hasChannelPermission(props.permissions, CHANNEL_PERMISSIONS.CHANNEL_SETTINGS),
  },
  {
    key: 'assistant',
    label: t('manage.nav.assistant'),
    icon: MagicStick,
    allowed: hasChannelPermission(props.permissions, CHANNEL_PERMISSIONS.AI_MANAGE),
  },
])

function go(section: string): void {
  if (section === props.activeSection) return
  void router.push({
    name: 'manage',
    params: {
      channelId: props.channelId,
      pathMatch: section === 'home' ? [] : [section],
    },
  })
}

function goChannel(): void {
  void router.push({ name: 'channel', params: { id: props.channelId } })
}

function handleSwitch(value: string | number | boolean | object): void {
  emit('switch-channel', String(value))
}

function openCreate(): void {
  if (!requireLogin(t('nav.loginToCreateChannel'))) return
  createDialogVisible.value = true
}

function handleCreated(channel: { id: string }): void {
  createDialogVisible.value = false
  void channelStore.fetchMyChannels(true)
  void router.push({ name: 'manage', params: { channelId: channel.id } })
}
</script>

<template>
  <aside class="manage-sidebar" :aria-label="t('channel.manageNav')">
    <div class="manage-sidebar__top">
      <RouterLink :to="{ name: 'channel', params: { id: channelId } }" class="manage-sidebar__back">
        <el-icon><ArrowLeft /></el-icon>{{ t('ai.backToChannel') }}</RouterLink>

      <el-select
        :model-value="channelId"
        class="manage-sidebar__select"
        :placeholder="t('channel.managePick')"
        @change="handleSwitch"
      >
        <el-option
          v-for="channel in manageableChannels"
          :key="channel.id"
          :label="channel.name"
          :value="channel.id"
        />
      </el-select>

      <div class="manage-sidebar__channel">
        <UserAvatar
          :src="manageableChannels.find((item) => item.id === channelId)?.avatarUrl"
          :name="channelName || manageableChannels.find((item) => item.id === channelId)?.name || t('common.channel')"
          :size="38"
          square
        />
        <div class="manage-sidebar__channel-meta">
          <strong>{{ channelName || t('manage.title') }}</strong>
          <small>
            {{
              t('manage.sidebar.memberCount', {
                n: formatCount(manageableChannels.find((item) => item.id === channelId)?.memberCount),
              })
            }}
          </small>
        </div>
      </div>
    </div>

    <nav class="manage-sidebar__nav" :aria-label="t('channel.manageFeatures')">
      <button
        v-for="item in navItems.filter((entry) => entry.allowed)"
        :key="item.key"
        type="button"
        class="manage-nav-item"
        :class="{ 'is-active': activeSection === item.key }"
        @click="go(item.key)"
      >
        <el-icon :size="16"><component :is="item.icon" /></el-icon>
        <span>{{ item.label }}</span>
      </button>
    </nav>

    <div class="manage-sidebar__footer">
      <el-button class="manage-sidebar__create" :icon="Plus" @click="openCreate">{{ t('manage.nav.createChannel') }}</el-button>
      <button type="button" class="manage-sidebar__home" @click="goChannel">
        <el-icon><HomeFilled /></el-icon>
        {{ t('manage.sidebar.channelHome') }}
      </button>
    </div>

    <CreateChannelDialog v-model="createDialogVisible" @created="handleCreated" />
  </aside>
</template>

<style scoped lang="scss">
.manage-sidebar {
  display: flex;
  flex-direction: column;
  min-height: calc(100vh - 120px);
  padding: var(--isles-space-4) var(--isles-space-3);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
}

.manage-sidebar__top {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
}

.manage-sidebar__back {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);

  &:hover {
    color: var(--isles-primary);
  }
}

.manage-sidebar__select {
  width: 100%;
}

.manage-sidebar__channel {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  padding: var(--isles-space-2) 0 var(--isles-space-3);
  border-bottom: 1px solid var(--isles-border);
}

.manage-sidebar__channel-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.35;

  strong {
    overflow: hidden;
    font-size: var(--isles-font-sm);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    color: var(--isles-text-muted);
    font-size: var(--isles-font-xs);
  }
}

.manage-sidebar__nav {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  padding: var(--isles-space-3) 0;
}

.manage-nav-item {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  width: 100%;
  padding: 9px var(--isles-space-2);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-sm);
  text-align: left;
  cursor: pointer;

  &:hover {
    color: var(--isles-primary);
    background: var(--isles-hover);
  }

  &.is-active {
    color: var(--isles-primary);
    font-weight: 600;
    background: var(--isles-primary-soft);
  }
}

.manage-sidebar__footer {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
  padding-top: var(--isles-space-3);
  border-top: 1px solid var(--isles-border);
}

.manage-sidebar__create {
  width: 100%;
}

.manage-sidebar__home {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  justify-content: center;
  padding: 4px;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
  background: transparent;
  border: none;
  cursor: pointer;
}

@media (max-width: 1023px) {
  .manage-sidebar {
    min-height: 0;
  }

  .manage-sidebar__nav {
    flex-direction: row;
    flex-wrap: wrap;
  }

  .manage-nav-item {
    width: auto;
  }
}
</style>
