<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref, type Component, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { channelsApi } from '@/api'
import { AppError, resolveErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useChannelStore } from '@/stores/channel'
import { canManageChannel, canManageChannelSummary, resolveChannelPermissions } from '@/utils/channelPermissions'
import type { Channel, ChannelSummary } from '@/types/models'
import ManageSidebar from '@/components/manage/ManageSidebar.vue'
import ManageHomeView from './manage/ManageHomeView.vue'
import ManageProfileView from './manage/ManageProfileView.vue'
import ManageContentView from './manage/ManageContentView.vue'
import ManageMembersView from './manage/ManageMembersView.vue'
import ManageBoardsView from './manage/ManageBoardsView.vue'
import ManageDataView from './manage/ManageDataView.vue'
import ManageLogsView from './manage/ManageLogsView.vue'
import ManageReportsView from './manage/ManageReportsView.vue'
import ManageAssistantView from './manage/ManageAssistantView.vue'
import ManageAppsView from './manage/ManageAppsView.vue'
import ManageActivitiesView from './manage/ManageActivitiesView.vue'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'

const { t } = useI18n()

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const channelStore = useChannelStore()

const channel = ref<Channel | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const errorStatus = ref(0)

const validSections = [
  'home',
  'profile',
  'content',
  'members',
  'boards',
  'data',
  'logs',
  'reports',
  'assistant',
  'apps',
  'activities',
]

const channelId = computed(() => String(route.params.channelId ?? ''))
const activeSection = computed(() => {
  const pathMatch = route.params.pathMatch
  const section = Array.isArray(pathMatch) && pathMatch.length > 0 ? String(pathMatch[0]) : 'home'
  return validSections.includes(section) ? section : 'home'
})

const permissions = computed(() => resolveChannelPermissions(channel.value, auth.currentUser?.id))
const canAccess = computed(() => Boolean(channel.value) && canManageChannel(permissions.value))
const manageableChannels = computed<ChannelSummary[]>(() =>
  channelStore.myChannels.filter((item) => canManageChannelSummary(item, auth.currentUser?.id)),
)

const sectionComponents: Record<string, Component> = {
  home: ManageHomeView,
  profile: ManageProfileView,
  content: ManageContentView,
  members: ManageMembersView,
  boards: ManageBoardsView,
  data: ManageDataView,
  logs: ManageLogsView,
  reports: ManageReportsView,
  assistant: ManageAssistantView,
  apps: ManageAppsView,
  activities: ManageActivitiesView,
}
const sectionComponent = computed(() => sectionComponents[activeSection.value] ?? ManageHomeView)

function sectionPath(section: string): string {
  return section === 'home' ? `/manage/${channelId.value}` : `/manage/${channelId.value}/${section}`
}

async function loadChannel(): Promise<void> {
  if (!channelId.value) return
  loading.value = true
  error.value = null
  errorStatus.value = 0
  try {
    channel.value = await channelsApi.getChannel(channelId.value)
  } catch (err) {
    channel.value = null
    const status = err instanceof AppError ? err.status : 0
    errorStatus.value = status
    error.value = status === 404 ? t('manage.page.notFound') : resolveErrorMessage(err, t('manage.page.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function loadManageableChannels(): Promise<void> {
  if (!auth.isAuthenticated) return
  await channelStore.fetchMyChannels(true)
}

function handleSwitchChannel(nextChannelId: string): void {
  void router.push({ name: 'manage', params: { channelId: nextChannelId } })
}

function handleChannelUpdated(updated: Channel): void {
  channel.value = updated
  channelStore.upsertMyChannel(updated)
}

watch(channelId, () => {
  void loadChannel()
})

watch(activeSection, () => {
  window.scrollTo({ top: 0, behavior: 'smooth' })
})

onMounted(() => {
  void loadChannel()
  void loadManageableChannels()
})
</script>

<template>
  <div class="page manage-page">
    <LoadingState v-if="loading" :rows="6" :tip="t('manage.loading')" />

    <ErrorState v-else-if="error" :message="error" :retry-text="t('error.reload')" @retry="loadChannel">
      <template #action>
        <el-button @click="$router.push({ name: 'channel', params: { id: channelId } })">{{ t('manage.page.back') }}</el-button>
      </template>
    </ErrorState>

    <ErrorState
      v-else-if="!canAccess"
      :message="t('channel.ownerOnlyManage')"
      :show-retry="false"
    >
      <template #action>
        <el-button type="primary" @click="$router.push({ name: 'channel', params: { id: channelId } })">{{ t('manage.page.back') }}</el-button>
      </template>
    </ErrorState>

    <template v-else-if="channel">
      <header class="manage-page__header">
        <div>
          <h1 class="page-title">{{ t('manage.title') }}</h1>
          <p class="page-subtitle">{{ t('manage.subtitle', { name: channel.name }) }}</p>
        </div>
        <el-tag size="small" effect="plain">{{ channel.myRoleName || t('channel.role.admin') }}</el-tag>
      </header>

      <div class="manage-layout">
        <ManageSidebar
          :channel-id="channelId"
          :channel-name="channel.name"
          :active-section="activeSection"
          :permissions="permissions"
          :manageable-channels="manageableChannels"
          @switch-channel="handleSwitchChannel"
        />

        <main class="manage-content" :aria-label="t('manage.contentLabel', { section: activeSection })">
          <component
            :is="sectionComponent"
            :channel="channel"
            :channel-id="channelId"
            @updated="handleChannelUpdated"
          />
        </main>
      </div>
    </template>

    <div v-else class="manage-page__empty">
      <el-empty :description="t('channel.infoUnavailable')" />
      <el-button type="primary" @click="router.push(sectionPath('home'))">{{ t('common.retry') }}</el-button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.manage-page__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--isles-space-3);
  margin-bottom: var(--isles-space-4);
}

.manage-layout {
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr);
  gap: var(--isles-space-4);
  align-items: start;
}

.manage-content {
  min-width: 0;
  padding: var(--isles-space-5);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
  box-shadow: var(--isles-shadow-sm);
}

.manage-page__empty {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-3);
  align-items: center;
  padding: var(--isles-space-10);
}

@media (max-width: 1023px) {
  .manage-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .manage-content {
    padding: var(--isles-space-4);
  }
}
</style>
