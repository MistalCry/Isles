<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { feedApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useChannelStore } from '@/stores/channel'
import { useCursorList } from '@/composables/useCursorList'
import { useInfiniteScroll } from '@/composables/useInfiniteScroll'
import { useRequireLogin } from '@/composables/useRequireLogin'
import { formatCount } from '@/utils/format'
import type { ChannelRecommendation, Post } from '@/types/models'
import PostCard from '@/components/PostCard.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const { t } = useI18n()

const router = useRouter()
const auth = useAuthStore()
const channelStore = useChannelStore()
const { isAuthenticated } = storeToRefs(auth)
const { requireLogin } = useRequireLogin()

const sentinel = ref<HTMLElement | null>(null)
const recommendations = ref<ChannelRecommendation[]>([])
const recommendationsLoading = ref(false)
const recommendationsError = ref<string | null>(null)
const joiningId = ref('')

const list = useCursorList<Post>((cursor) => feedApi.getHot({ cursor, limit: 20 }))

const showLoginForRecommendations = computed(() => !isAuthenticated.value)

function canLoadMore(): boolean {
  return !list.loading.value && list.hasMore.value
}

useInfiniteScroll(
  sentinel,
  () => {
    void list.loadMore()
  },
  canLoadMore,
)

async function loadRecommendations(refresh = false): Promise<void> {
  if (!isAuthenticated.value) {
    recommendations.value = []
    return
  }
  recommendationsLoading.value = true
  recommendationsError.value = null
  try {
    // 手动刷新带 seed，让后端在候选窗口内轮换，避免每次都拿到同一批
    recommendations.value = await feedApi.getRecommendations(6, refresh ? Date.now() : undefined)
  } catch (err) {
    recommendationsError.value = resolveErrorMessage(err, t('hot.recommendFailed'))
  } finally {
    recommendationsLoading.value = false
  }
}

async function handleJoin(recommendation: ChannelRecommendation): Promise<void> {
  if (!requireLogin(t('channel.page.joinLoginRequired'))) return
  if (channelStore.isJoined(recommendation.channel.id)) {
    void router.push({ name: 'channel', params: { id: recommendation.channel.id } })
    return
  }
  joiningId.value = recommendation.channel.id
  try {
    await channelStore.joinChannel(recommendation.channel.id)
    await channelStore.fetchMyChannels(true)
    ElMessage.success(t('channel.page.joinedToast', { name: recommendation.channel.name }))
  } catch {
    // 拦截器已提示
  } finally {
    joiningId.value = ''
  }
}

function goChannel(recommendation: ChannelRecommendation): void {
  void router.push({ name: 'channel', params: { id: recommendation.channel.id } })
}

onMounted(() => {
  void list.refresh()
  void loadRecommendations(false)
})
</script>

<template>
  <div class="page hot-page">
    <header class="page-header">
      <div>
        <h1 class="page-title">{{ t('nav.hot') }}</h1>
        <p class="page-subtitle">{{ t('hot.subtitle') }}</p>
      </div>
      <el-button :icon="Refresh" @click="list.refresh()">{{ t('common.refresh') }}</el-button>
    </header>

    <div class="hot-layout">
      <section class="hot-main" :aria-label="t('manage.data.topPosts')">
        <LoadingState v-if="list.initialLoading.value" :rows="5" :tip="t('hot.loading')" />
        <ErrorState v-else-if="list.error.value" :message="list.error.value" @retry="list.refresh()" />
        <EmptyState
          v-else-if="list.items.value.length === 0"
          :title="t('hot.empty')"
          :description="t('hot.emptyHint')"
        />
        <div v-else class="hot-list">
          <PostCard v-for="post in list.items.value" :key="post.id" :post="post" show-channel />
          <div ref="sentinel" class="hot-list__more">
            <el-button
              v-if="list.hasMore.value"
              text
              type="primary"
              :loading="list.loading.value"
              @click="list.loadMore()"
            >
              {{ t('hot.loadMore') }}
            </el-button>
            <span v-else class="muted">{{ t('hot.end') }}</span>
          </div>
        </div>
      </section>

      <aside class="hot-side" :aria-label="t('hot.recommend')">
        <div class="isles-card recommendation-panel">
          <header class="recommendation-panel__head">
            <h2>{{ t('hot.recommend') }}</h2>
            <el-button text size="small" :icon="Refresh" :loading="recommendationsLoading" @click="loadRecommendations(true)">{{ t('common.refresh') }}</el-button>
          </header>

          <p v-if="showLoginForRecommendations" class="muted recommendation-panel__login">
            {{ t('hot.loginForRecommendations') }}
          </p>

          <template v-else>
            <LoadingState v-if="recommendationsLoading && recommendations.length === 0" :rows="3" />
            <ErrorState
              v-else-if="recommendationsError && recommendations.length === 0"
              :message="recommendationsError"
              @retry="loadRecommendations"
            />
            <EmptyState
              v-else-if="recommendations.length === 0"
              compact
              :title="t('hot.recommendEmpty')"
              :description="t('hot.recommendEmptyHint')"
            />
            <ul v-else class="recommendation-list">
              <li v-for="recommendation in recommendations" :key="recommendation.channel.id">
                <div class="recommendation-item">
                  <button type="button" class="recommendation-item__main" @click="goChannel(recommendation)">
                    <UserAvatar
                      :src="recommendation.channel.avatarUrl"
                      :name="recommendation.channel.name"
                      :size="40"
                      square
                    />
                    <span class="recommendation-item__meta">
                      <strong class="line-clamp-1">{{ recommendation.channel.name }}</strong>
                      <small class="muted">{{ t('hot.memberCount', { n: formatCount(recommendation.channel.memberCount) }) }}</small>
                      <small class="recommendation-item__reason line-clamp-2">{{ recommendation.reason }}</small>
                    </span>
                  </button>
                  <el-button
                    v-if="!channelStore.isJoined(recommendation.channel.id)"
                    size="small"
                    type="primary"
                    plain
                    :loading="joiningId === recommendation.channel.id"
                    @click="handleJoin(recommendation)"
                  >{{ t('channel.card.join') }}</el-button>
                  <el-tag v-else size="small" type="success" effect="plain">{{ t('channel.joined') }}</el-tag>
                </div>
              </li>
            </ul>
          </template>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped lang="scss">
.hot-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: var(--isles-space-5);
  align-items: start;
}

.hot-list {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-4);
}

.hot-list__more {
  display: flex;
  justify-content: center;
  padding: var(--isles-space-3);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.hot-side {
  position: sticky;
  top: var(--isles-space-4);
}

.recommendation-panel {
  padding: var(--isles-space-4);
}

.recommendation-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--isles-space-3);

  h2 {
    font-size: var(--isles-font-md);
    font-weight: 600;
  }
}

.recommendation-panel__login {
  font-size: var(--isles-font-xs);
}

.recommendation-list {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-3);
}

.recommendation-item {
  display: flex;
  gap: var(--isles-space-2);
  align-items: flex-start;
}

.recommendation-item__main {
  display: flex;
  flex: 1;
  gap: var(--isles-space-2);
  align-items: center;
  min-width: 0;
  padding: 0;
  text-align: left;
  background: transparent;
  border: none;
  cursor: pointer;

  &:hover strong {
    color: var(--isles-primary);
  }
}

.recommendation-item__meta {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  line-height: 1.35;

  strong {
    font-size: var(--isles-font-sm);
  }

  small {
    font-size: var(--isles-font-xs);
  }
}

.recommendation-item__reason {
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-xs);
}

@media (max-width: 1023px) {
  .hot-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .hot-side {
    position: static;
    order: -1;
  }
}
</style>
