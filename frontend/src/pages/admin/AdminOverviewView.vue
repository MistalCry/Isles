<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Refresh } from '@element-plus/icons-vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingState from '@/components/LoadingState.vue'
import AdminTrendChart from '@/components/admin/AdminTrendChart.vue'
import {
  deleteAdminHotKeyword,
  getAdminOverview,
  getAdminTrend,
  listAdminHotKeywords,
  type AdminHotKeyword,
  type AdminOverview,
  type AdminTrendPoint,
} from './adminApi'
import { resolveErrorMessage } from '@/api/client'
import { hasPermission } from './adminPermissions'
import { useAdminAccess } from '@/composables/useAdminAccess'
import { formatCount, formatDateTime } from '@/utils/format'

const { t } = useI18n()
const { identity } = useAdminAccess()
/**
 * 概览页只要 STATS_READ，但「下线热词」走 CONFIG_WRITE：
 * MODERATOR 这类角色点下去必 403，所以按接口权限点单独门控。
 */
const canRemoveKeyword = computed(() => hasPermission(identity.value, 'CONFIG_WRITE'))

const overview = ref<AdminOverview | null>(null)
const trend = ref<AdminTrendPoint[]>([])
const hotKeywords = ref<AdminHotKeyword[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
/** 趋势默认 30 天；后端上限 90，这里只暴露 7/30/90 三档 */
const trendDays = ref(30)

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
    // 三块数据互不阻塞：趋势/热词失败不应让整页空白
    const [summary, trendData, keywords] = await Promise.all([
      getAdminOverview(),
      getAdminTrend(trendDays.value).catch(() => [] as AdminTrendPoint[]),
      listAdminHotKeywords(10).catch(() => [] as AdminHotKeyword[]),
    ])
    overview.value = summary
    trend.value = trendData
    hotKeywords.value = keywords
  } catch (cause) {
    error.value = resolveErrorMessage(cause, t('admin.overview.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function changeDays(days: number): Promise<void> {
  trendDays.value = days
  try {
    trend.value = await getAdminTrend(days)
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.trend.loadFailed')))
  }
}

async function removeKeyword(item: AdminHotKeyword): Promise<void> {
  try {
    await deleteAdminHotKeyword(item.id)
    hotKeywords.value = hotKeywords.value.filter((entry) => entry.id !== item.id)
    ElMessage.success(t('admin.trend.keywordDeleted'))
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.trend.keywordDeleteFailed')))
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
      <section class="overview-section">
        <header class="overview-section__head">
          <div>
            <h3>{{ t('admin.trend.title') }}</h3>
            <p class="muted">{{ t('admin.trend.subtitle') }}</p>
          </div>
          <el-radio-group :model-value="trendDays" size="small" @update:model-value="changeDays(Number($event))">
            <el-radio-button :value="7">{{ t('admin.trend.days7') }}</el-radio-button>
            <el-radio-button :value="30">{{ t('admin.trend.days30') }}</el-radio-button>
            <el-radio-button :value="90">{{ t('admin.trend.days90') }}</el-radio-button>
          </el-radio-group>
        </header>
        <AdminTrendChart v-if="trend.length > 0" :points="trend" />
        <el-empty v-else :description="t('admin.trend.empty')" />
      </section>

      <section class="overview-section">
        <header class="overview-section__head">
          <div>
            <h3>{{ t('admin.trend.hotKeywords') }}</h3>
            <p class="muted">{{ t('admin.trend.hotKeywordsHint') }}</p>
          </div>
        </header>
        <el-empty v-if="hotKeywords.length === 0" :description="t('admin.trend.hotKeywordsEmpty')" />
        <ul v-else class="hot-keywords">
          <li v-for="item in hotKeywords" :key="item.id" class="hot-keyword">
            <span class="hot-keyword__text">{{ item.keyword }}</span>
            <span class="muted">{{ t('admin.trend.searchCount', { n: item.searchCount }) }}</span>
            <span class="muted">{{ formatDateTime(item.lastSearchedAt) }}</span>
            <el-button
              size="small"
              link
              type="danger"
              :icon="Delete"
              :disabled="!canRemoveKeyword"
              @click="removeKeyword(item)"
            >
              {{ t('common.delete') }}
            </el-button>
          </li>
        </ul>
      </section>

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

.overview-section {
  margin-top: var(--isles-space-5);
  padding: var(--isles-space-4);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
}

.overview-section__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: var(--isles-space-3);

  h3 {
    font-size: var(--isles-font-md);
    font-weight: 600;
  }
}

.hot-keywords {
  display: flex;
  flex-direction: column;
}

.hot-keyword {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 100px 160px 60px;
  gap: var(--isles-space-2);
  align-items: center;
  padding: var(--isles-space-2) 0;
  font-size: var(--isles-font-sm);
  border-bottom: 1px dashed var(--isles-border);

  &:last-child {
    border-bottom: none;
  }
}

.hot-keyword__text {
  font-weight: 600;
}
</style>
