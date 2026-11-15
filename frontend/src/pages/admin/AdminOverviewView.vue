<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingState from '@/components/LoadingState.vue'
import { getAdminOverview, type AdminOverview } from './adminApi'
import { resolveErrorMessage } from '@/api/client'
import { formatCount } from '@/utils/format'

const { t } = useI18n()

const overview = ref<AdminOverview | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

const cards = computed(() => {
  if (!overview.value) return []
  const data = overview.value
  return [
    { label: t('admin.overview.users'), value: formatCount(data.userCount), hint: t('admin.overview.newToday', { n: data.todayNewUsers }) },
    { label: t('admin.overview.channels'), value: formatCount(data.channelCount), hint: t('admin.overview.channelsHint') },
    { label: t('admin.overview.posts'), value: formatCount(data.postCount), hint: t('admin.overview.newToday', { n: data.todayNewPosts }) },
    { label: t('admin.overview.comments'), value: formatCount(data.commentCount), hint: t('admin.overview.commentsHint') },
    { label: t('admin.overview.activeUsers'), value: formatCount(data.todayActiveUsers), hint: t('admin.overview.activeHint') },
    { label: t('admin.overview.aiSessions'), value: formatCount(data.aiConversationCount), hint: t('admin.overview.aiMessages', { n: data.aiMessageCount }) },
    {
      label: t('admin.overview.pendingReports'),
      value: data.reportTableReady ? formatCount(data.pendingReportCount) : '—',
      hint: data.reportTableReady ? t('admin.overview.pendingHint') : t('admin.overview.reportsNotReady'),
    },
  ]
})

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    overview.value = await getAdminOverview()
  } catch (cause) {
    error.value = resolveErrorMessage(cause, t('admin.overview.loadFailed'))
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="admin-overview">
    <header class="admin-section-head">
      <div>
        <h2 class="admin-section-title">{{ t('admin.overview.title') }}</h2>
        <p class="admin-section-subtitle">{{ t('admin.overview.subtitle') }}</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('common.refresh') }}</el-button>
    </header>

    <LoadingState v-if="loading && !overview" :rows="4" :tip="t('admin.overview.loading')" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <template v-else-if="overview">
      <div class="overview-grid">
        <article v-for="card in cards" :key="card.label" class="overview-card">
          <p class="overview-card__label">{{ card.label }}</p>
          <p class="overview-card__value">{{ card.value }}</p>
          <p class="overview-card__hint">{{ card.hint }}</p>
        </article>
      </div>
      <el-alert
        v-if="!overview.reportTableReady"
        class="overview-alert"
        type="info"
        show-icon
        :closable="false"
        :title="t('admin.reports.notReady')"
        :description="t('admin.reports.notReadyHint')"
      />
    </template>
  </section>
</template>

<style scoped lang="scss">
.admin-section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: var(--isles-space-4);
}

.admin-section-title {
  font-size: var(--isles-font-lg);
  font-weight: 600;
}

.admin-section-subtitle {
  margin-top: var(--isles-space-1);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-sm);
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: var(--isles-space-3);
}

.overview-card {
  padding: var(--isles-space-4);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
  box-shadow: var(--isles-shadow-sm);
}

.overview-card__label {
  color: var(--isles-text-muted);
  font-size: var(--isles-font-sm);
}

.overview-card__value {
  margin-top: var(--isles-space-1);
  color: var(--isles-primary);
  font-size: var(--isles-font-display);
  font-weight: 600;
  line-height: 1.2;
}

.overview-card__hint {
  margin-top: var(--isles-space-1);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.overview-alert {
  margin-top: var(--isles-space-4);
}
</style>
