<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Document, TrendCharts, User, View } from '@element-plus/icons-vue'
import { manageApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { formatCount } from '@/utils/format'
import type { Channel, ChannelOverview } from '@/types/models'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'

const { t } = useI18n()

const props = defineProps<{
  channel: Channel
  channelId: string
}>()

const router = useRouter()
const overview = ref<ChannelOverview | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

const cards = computed(() => {
  const data = overview.value
  return [
    { key: 'members', label: t('common.memberCount'), value: data?.memberCount ?? props.channel.memberCount ?? 0, icon: User },
    { key: 'posts', label: t('common.postCount'), value: data?.postCount ?? props.channel.postCount ?? 0, icon: Document },
    { key: 'new-members', label: t('manage.home.newMembers'), value: data?.yesterdayNewMembers ?? 0, icon: TrendCharts },
    { key: 'visitors', label: t('common.yesterdayVisitors'), value: data?.yesterdayVisitors ?? 0, icon: View },
    { key: 'new-posts', label: t('manage.home.newPosts'), value: data?.yesterdayNewPosts ?? 0, icon: Document },
    { key: 'active-rate', label: t('common.activeRate'), value: formatRate(data?.activeRate), icon: TrendCharts },
  ]
})

function formatRate(value?: number): string {
  if (value === undefined || value === null) return '—'
  const percent = value <= 1 ? value * 100 : value
  return `${percent.toFixed(1)}%`
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    overview.value = await manageApi.getManageOverview(props.channelId)
  } catch (err) {
    error.value = resolveErrorMessage(err, t('manage.home.loadFailed'))
  } finally {
    loading.value = false
  }
}

function go(section: string): void {
  void router.push({
    name: 'manage',
    params: { channelId: props.channelId, pathMatch: section === 'home' ? [] : [section] },
  })
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="manage-section">
    <header class="manage-section__header">
      <div>
        <h2>{{ t('manage.nav.overview') }}</h2>
        <p class="muted">{{ t('manage.home.subtitle') }}</p>
      </div>
      <el-button text type="primary" @click="load">{{ t('manage.home.refresh') }}</el-button>
    </header>

    <LoadingState v-if="loading && !overview" :rows="3" :tip="t('manage.home.loading')" />
    <ErrorState v-else-if="error && !overview" :message="error" @retry="load" />

    <template v-else>
      <div class="overview-grid">
        <div v-for="card in cards" :key="card.key" class="overview-card isles-card">
          <el-icon :size="20" class="overview-card__icon"><component :is="card.icon" /></el-icon>
          <span class="overview-card__label">{{ card.label }}</span>
          <strong class="overview-card__value">
            {{ typeof card.value === 'number' ? formatCount(card.value) : card.value }}
          </strong>
        </div>
      </div>

      <div class="quick-links">
        <button type="button" class="quick-link isles-card" @click="go('content')">
          <strong>{{ t('manage.nav.content') }}</strong>
          <span class="muted">{{ t('manage.home.cardContent') }}</span>
        </button>
        <button type="button" class="quick-link isles-card" @click="go('members')">
          <strong>{{ t('manage.nav.members') }}</strong>
          <span class="muted">{{ t('manage.home.cardMembers') }}</span>
        </button>
        <button type="button" class="quick-link isles-card" @click="go('data')">
          <strong>{{ t('manage.nav.data') }}</strong>
          <span class="muted">{{ t('manage.home.cardData') }}</span>
        </button>
        <button type="button" class="quick-link isles-card" @click="go('logs')">
          <strong>{{ t('manage.nav.logs') }}</strong>
          <span class="muted">{{ t('manage.home.cardLogs') }}</span>
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped lang="scss">
.manage-section__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--isles-space-3);
  margin-bottom: var(--isles-space-4);

  h2 {
    font-size: var(--isles-font-lg);
    font-weight: 600;
  }

  p {
    margin-top: 2px;
    font-size: var(--isles-font-xs);
  }
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(170px, 1fr));
  gap: var(--isles-space-3);
}

.overview-card {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-1);
  padding: var(--isles-space-4);
}

.overview-card__icon {
  color: var(--isles-primary);
}

.overview-card__label {
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.overview-card__value {
  font-size: var(--isles-font-xl);
  font-weight: 600;
  line-height: var(--isles-line-tight);
}

.quick-links {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--isles-space-3);
  margin-top: var(--isles-space-4);
}

.quick-link {
  display: flex;
  flex-direction: column;
  gap: 2px;
  align-items: flex-start;
  padding: var(--isles-space-4);
  text-align: left;
  cursor: pointer;

  strong {
    font-size: var(--isles-font-sm);
  }

  span {
    font-size: var(--isles-font-xs);
  }

  &:hover {
    border-color: var(--isles-border-strong);
    box-shadow: var(--isles-shadow-md);
  }
}
</style>
