<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { feedApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useCursorList } from '@/composables/useCursorList'
import { useInfiniteScroll } from '@/composables/useInfiniteScroll'
import type { FeedSort, Post } from '@/types/models'
import PostCard from '@/components/PostCard.vue'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const { t } = useI18n()

const router = useRouter()
const auth = useAuthStore()
const { isAuthenticated } = storeToRefs(auth)

const sort = ref<FeedSort>('latest')
const sentinel = ref<HTMLElement | null>(null)

const list = useCursorList<Post>((cursor) =>
  feedApi.getFeed({ sort: sort.value, cursor, limit: 20 }),
)

const needsLogin = computed(
  () => !isAuthenticated.value || list.errorStatus.value === 401,
)
const errorMessage = computed(() => {
  if (list.errorStatus.value === 401) return t('feed.sessionExpired')
  return list.error.value ?? ''
})

function canLoadMore(): boolean {
  return isAuthenticated.value && !list.loading.value && list.hasMore.value
}

useInfiniteScroll(
  sentinel,
  () => {
    void list.loadMore()
  },
  canLoadMore,
)

function loadFeed(): void {
  if (!isAuthenticated.value) return
  void list.refresh()
}

onMounted(loadFeed)

watch(isAuthenticated, (loggedIn) => {
  if (loggedIn) {
    loadFeed()
  }
})

watch(sort, () => {
  if (isAuthenticated.value) {
    void list.refresh()
  }
})

function goLogin(): void {
  void router.push({ name: 'login', query: { redirect: '/feed' } })
}

function goDiscover(): void {
  void router.push({ name: 'search' })
}
</script>

<template>
  <div class="page feed-page">
    <header class="page-header">
      <div>
        <h1 class="page-title">{{ t('nav.feed') }}</h1>
        <p class="page-subtitle">{{ t('feed.subtitle') }}</p>
      </div>
      <el-radio-group v-if="isAuthenticated" v-model="sort" size="small" :aria-label="t('channel.feedSort')">
        <el-radio-button value="latest">{{ t('comment.sort.latest') }}</el-radio-button>
        <el-radio-button value="hot">{{ t('channel.page.hot') }}</el-radio-button>
      </el-radio-group>
    </header>

    <EmptyState
      v-if="needsLogin"
      :title="t('channel.feedLoginRequired')"
      :description="t('channel.feedJoinHint')"
    >
      <template #action>
        <el-button type="primary" @click="goLogin">{{ t('channel.page.loginRegister') }}</el-button>
      </template>
    </EmptyState>

    <template v-else>
      <LoadingState v-if="list.initialLoading.value" :rows="4" :tip="t('channel.feedLoading')" />

      <ErrorState
        v-else-if="list.error.value"
        :message="errorMessage"
        @retry="loadFeed"
      />

      <EmptyState
        v-else-if="list.items.value.length === 0"
        :title="t('channel.feedEmpty')"
        :description="t('channel.feedEmptyHint')"
      >
        <template #action>
          <el-button type="primary" @click="goDiscover">{{ t('feed.explore') }}</el-button>
        </template>
      </EmptyState>

      <div v-else class="feed-page__list">
        <PostCard
          v-for="post in list.items.value"
          :key="post.id"
          :post="post"
          show-channel
        />

        <div ref="sentinel" class="feed-page__load-more">
          <el-button
            v-if="list.hasMore.value"
            text
            type="primary"
            :loading="list.loading.value"
            @click="list.loadMore()"
          >
            {{ t('feed.loadMore') }}
          </el-button>
          <span v-else class="muted">{{ t('feed.empty') }}</span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss">
.feed-page__list {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-4);
}

.feed-page__load-more {
  display: flex;
  justify-content: center;
  padding: var(--isles-space-4) 0 var(--isles-space-6);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}
</style>
