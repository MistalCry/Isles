<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Document, TrendCharts, User, View } from '@element-plus/icons-vue'
import { manageApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { formatCount } from '@/utils/format'
import type { Channel, ChannelOverview } from '@/types/models'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'

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
    { key: 'members', label: '成员数', value: data?.memberCount ?? props.channel.memberCount ?? 0, icon: User },
    { key: 'posts', label: '帖子数', value: data?.postCount ?? props.channel.postCount ?? 0, icon: Document },
    { key: 'new-members', label: '昨日新增成员', value: data?.yesterdayNewMembers ?? 0, icon: TrendCharts },
    { key: 'visitors', label: '昨日访问人数', value: data?.yesterdayVisitors ?? 0, icon: View },
    { key: 'new-posts', label: '昨日新增帖子', value: data?.yesterdayNewPosts ?? 0, icon: Document },
    { key: 'active-rate', label: '活跃率', value: formatRate(data?.activeRate), icon: TrendCharts },
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
    error.value = resolveErrorMessage(err, '概览数据加载失败')
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
        <h2>首页概览</h2>
        <p class="muted">频道整体运营数据与快捷入口</p>
      </div>
      <el-button text type="primary" @click="load">刷新数据</el-button>
    </header>

    <LoadingState v-if="loading && !overview" :rows="3" tip="正在加载概览数据…" />
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
          <strong>内容管理</strong>
          <span class="muted">置顶 / 精华 / 删除与批量操作</span>
        </button>
        <button type="button" class="quick-link isles-card" @click="go('members')">
          <strong>成员与身份组</strong>
          <span class="muted">成员搜索、身份组与禁言管理</span>
        </button>
        <button type="button" class="quick-link isles-card" @click="go('data')">
          <strong>数据中心</strong>
          <span class="muted">趋势与内容/成员分析</span>
        </button>
        <button type="button" class="quick-link isles-card" @click="go('logs')">
          <strong>管理日志</strong>
          <span class="muted">频道内操作审计记录</span>
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
