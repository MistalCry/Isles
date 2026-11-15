<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref, watch } from 'vue'
import { manageApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { formatCount } from '@/utils/format'
import type { Channel, ManageStats } from '@/types/models'
import StatsTrendChart from '@/components/manage/StatsTrendChart.vue'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'

const { t } = useI18n()

const props = defineProps<{
  channel: Channel
  channelId: string
}>()

const days = ref(7)
const stats = ref<ManageStats | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

const overview = computed(() => stats.value?.overview ?? null)
const memberCount = computed(() => overview.value?.memberCount ?? props.channel.memberCount ?? 0)
const postCount = computed(() => overview.value?.postCount ?? props.channel.postCount ?? 0)
const activeRate = computed(() => {
  const value = overview.value?.activeRate
  if (value === undefined || value === null) return '—'
  return `${(value <= 1 ? value * 100 : value).toFixed(1)}%`
})

const maxDistribution = computed(() => {
  const values = [
    ...(stats.value?.boardDistribution ?? []).map((item) => item.value),
    ...(stats.value?.roleDistribution ?? []).map((item) => item.value),
  ]
  return Math.max(1, ...values)
})

function distributionPercent(value: number): string {
  return `${Math.max(2, Math.round((value / maxDistribution.value) * 100))}%`
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    stats.value = await manageApi.getManageStats(props.channelId, days.value)
  } catch (err) {
    error.value = resolveErrorMessage(err, t('manage.data.loadFailed'))
    stats.value = null
  } finally {
    loading.value = false
  }
}

watch(days, () => {
  void load()
})

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="manage-section">
    <header class="manage-section__header">
      <div>
        <h2>{{ t('manage.nav.data') }}</h2>
        <p class="muted">{{ t('manage.data.subtitle') }}</p>
      </div>
      <el-radio-group v-model="days" size="small">
        <el-radio-button :value="7">{{ t('manage.data.last7') }}</el-radio-button>
        <el-radio-button :value="30">{{ t('manage.data.last30') }}</el-radio-button>
      </el-radio-group>
    </header>

    <LoadingState v-if="loading && !stats" :rows="5" :tip="t('manage.data.loading')" />
    <ErrorState v-else-if="error && !stats" :message="error" @retry="load" />

    <template v-else>
      <div class="stat-cards">
        <div class="stat-card isles-card">
          <span class="muted">{{ t('common.memberCount') }}</span>
          <strong>{{ formatCount(memberCount) }}</strong>
        </div>
        <div class="stat-card isles-card">
          <span class="muted">{{ t('common.postCount') }}</span>
          <strong>{{ formatCount(postCount) }}</strong>
        </div>
        <div class="stat-card isles-card">
          <span class="muted">{{ t('common.yesterdayVisitors') }}</span>
          <strong>{{ formatCount(overview?.yesterdayVisitors) }}</strong>
        </div>
        <div class="stat-card isles-card">
          <span class="muted">{{ t('common.activeRate') }}</span>
          <strong>{{ activeRate }}</strong>
        </div>
        <div class="stat-card isles-card">
          <span class="muted">{{ t('manage.data.totalInteractions') }}</span>
          <strong>{{ formatCount(overview?.totalInteractions) }}</strong>
        </div>
      </div>

      <div class="data-grid">
        <div class="isles-card data-panel">
          <StatsTrendChart :points="stats?.trend ?? []" metric="visits" :label="t('manage.data.visitorTrend')" />
        </div>
        <div class="isles-card data-panel">
          <StatsTrendChart :points="stats?.trend ?? []" metric="posts" :label="t('manage.data.postTrend')" />
        </div>
      </div>

      <div class="data-grid">
        <div class="isles-card data-panel">
          <h3 class="data-panel__title">{{ t('manage.data.boardDist') }}</h3>
          <div v-if="stats?.boardDistribution?.length" class="distribution">
            <div v-for="item in stats.boardDistribution" :key="item.name" class="distribution__row">
              <span class="distribution__name">{{ item.name }}</span>
              <span class="distribution__bar"><span :style="{ width: distributionPercent(item.value) }" /></span>
              <span class="distribution__value">{{ item.value }}</span>
            </div>
          </div>
          <p v-else class="muted data-panel__empty">{{ t('manage.data.boardEmpty') }}</p>
        </div>

        <div class="isles-card data-panel">
          <h3 class="data-panel__title">{{ t('manage.data.roleDist') }}</h3>
          <div v-if="stats?.roleDistribution?.length" class="distribution">
            <div v-for="item in stats.roleDistribution" :key="item.name" class="distribution__row">
              <span class="distribution__name">{{ item.name }}</span>
              <span class="distribution__bar"><span :style="{ width: distributionPercent(item.value) }" /></span>
              <span class="distribution__value">{{ item.value }}</span>
            </div>
          </div>
          <p v-else class="muted data-panel__empty">{{ t('manage.data.roleEmpty') }}</p>
        </div>
      </div>

      <div class="isles-card data-panel">
        <h3 class="data-panel__title">{{ t('manage.data.topPosts') }}</h3>
        <ul v-if="stats?.topPosts?.length" class="top-posts">
          <li v-for="post in stats.topPosts" :key="post.id">
            <RouterLink :to="{ name: 'post', params: { id: post.id } }" class="top-posts__link line-clamp-1">
              {{ post.title || post.content.slice(0, 50) }}
            </RouterLink>
            <span class="muted">
              {{
                t('manage.content.viewsLikes', {
                  views: formatCount(post.viewCount),
                  likes: formatCount(post.likeCount),
                })
              }}
            </span>
          </li>
        </ul>
        <p v-else class="muted data-panel__empty">{{ t('manage.data.topPostsEmpty') }}</p>
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

.stat-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: var(--isles-space-3);
}

.stat-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--isles-space-4);

  span {
    font-size: var(--isles-font-xs);
  }

  strong {
    font-size: var(--isles-font-xl);
    font-weight: 600;
  }
}

.data-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: var(--isles-space-4);
  margin-top: var(--isles-space-4);
}

.data-panel {
  padding: var(--isles-space-4);
}

.data-panel__title {
  margin-bottom: var(--isles-space-3);
  font-size: var(--isles-font-md);
  font-weight: 600;
}

.data-panel__empty {
  padding: var(--isles-space-4) 0;
  font-size: var(--isles-font-xs);
}

.distribution {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
}

.distribution__row {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  font-size: var(--isles-font-xs);
}

.distribution__name {
  width: 96px;
  overflow: hidden;
  color: var(--isles-text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.distribution__bar {
  flex: 1;
  height: 8px;
  overflow: hidden;
  background: var(--isles-surface-muted);
  border-radius: var(--isles-radius-pill);

  span {
    display: block;
    height: 100%;
    background: var(--isles-primary);
    border-radius: inherit;
  }
}

.distribution__value {
  width: 40px;
  color: var(--isles-text-muted);
  text-align: right;
}

.top-posts {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);

  li {
    display: flex;
    gap: var(--isles-space-3);
    align-items: center;
    justify-content: space-between;
    font-size: var(--isles-font-sm);
  }

  span {
    flex: none;
    font-size: var(--isles-font-xs);
  }
}

.top-posts__link {
  flex: 1;
  min-width: 0;
  color: var(--isles-text);

  &:hover {
    color: var(--isles-primary);
  }
}
</style>
