<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingState from '@/components/LoadingState.vue'
import { getAdminOverview, type AdminOverview } from './adminApi'
import { resolveErrorMessage } from '@/api/client'
import { formatCount } from '@/utils/format'

const overview = ref<AdminOverview | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

const cards = computed(() => {
  if (!overview.value) return []
  const data = overview.value
  return [
    { label: '用户总数', value: formatCount(data.userCount), hint: `今日新增 ${data.todayNewUsers}` },
    { label: '频道总数', value: formatCount(data.channelCount), hint: '仅统计运营中的频道' },
    { label: '帖子总数', value: formatCount(data.postCount), hint: `今日新增 ${data.todayNewPosts}` },
    { label: '评论总数', value: formatCount(data.commentCount), hint: '已发布评论' },
    { label: '今日活跃用户', value: formatCount(data.todayActiveUsers), hint: '发帖/评论/点赞去重（UTC 自然日）' },
    { label: 'AI 会话', value: formatCount(data.aiConversationCount), hint: `消息 ${data.aiMessageCount} 条` },
    {
      label: '待处理举报',
      value: data.reportTableReady ? formatCount(data.pendingReportCount) : '—',
      hint: data.reportTableReady ? 'PENDING 状态' : '举报表尚未就绪（等待 V2 迁移）',
    },
  ]
})

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    overview.value = await getAdminOverview()
  } catch (cause) {
    error.value = resolveErrorMessage(cause, '平台数据加载失败')
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
        <h2 class="admin-section-title">平台数据概览</h2>
        <p class="admin-section-subtitle">全站规模与今日活跃的近似统计（docs/03 §4.16 FR-PADM-03）</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </header>

    <LoadingState v-if="loading && !overview" :rows="4" tip="正在统计平台数据…" />
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
        title="举报数据尚未就绪"
        description="reports 表由 WP1（V2 迁移）创建，当前接口已按契约预留：表就绪后举报列表与处置会自动可用，无需改动前端。"
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
