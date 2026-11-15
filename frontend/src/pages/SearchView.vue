<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { searchApi, channelsApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { useCursorList } from '@/composables/useCursorList'
import type { ChannelSummary, Post } from '@/types/models'
import SearchPanel from '@/components/SearchPanel.vue'
import ChannelCard from '@/components/ChannelCard.vue'
import PostCard from '@/components/PostCard.vue'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

type SearchTab = 'all' | 'channel' | 'post'

const route = useRoute()
const router = useRouter()

const activeTab = ref<SearchTab>(normalizeTab(route.query.type))
const keyword = computed(() => String(route.query.q ?? '').trim())
const channelId = computed(() => String(route.query.channelId ?? '').trim())
const scope = computed<'global' | 'channel'>(() =>
  route.query.scope === 'channel' && channelId.value ? 'channel' : 'global',
)
const channelName = ref('')
const channelSort = ref('relevance')
const postSort = ref('relevance')

const channelSortOptions = [
  { value: 'relevance', label: '相关度' },
  { value: 'memberCount', label: '按人数' },
  { value: 'activity', label: '按活跃度' },
]
const postSortOptions = [
  { value: 'relevance', label: '相关度' },
  { value: 'createdAt', label: '按发布时间' },
]

const allLoading = ref(false)
const allError = ref('')
const allResult = ref<{ channels: ChannelSummary[]; posts: Post[] } | null>(null)

const channelList = useCursorList<ChannelSummary>((cursor) =>
  searchApi.searchChannels({
    q: keyword.value,
    cursor,
    limit: 20,
    scope: scope.value,
    channelId: channelId.value || undefined,
    sort: channelSort.value,
  }),
)
const postList = useCursorList<Post>((cursor) =>
  searchApi.searchPosts({
    q: keyword.value,
    cursor,
    limit: 20,
    scope: scope.value,
    channelId: channelId.value || undefined,
    sort: postSort.value,
  }),
)

const tabs: Array<{ key: SearchTab; label: string }> = [
  { key: 'all', label: '综合' },
  { key: 'channel', label: '频道' },
  { key: 'post', label: '帖子' },
]

const hasAnyResult = computed(() => {
  if (!allResult.value) return false
  return allResult.value.channels.length > 0 || allResult.value.posts.length > 0
})

function normalizeTab(value: unknown): SearchTab {
  if (value === 'channel') return 'channel'
  if (value === 'post') return 'post'
  return 'all'
}

async function searchAllResults(): Promise<void> {
  if (!keyword.value) return
  allLoading.value = true
  allError.value = ''
  try {
    allResult.value = await searchApi.searchAll(keyword.value, {
      scope: scope.value,
      channelId: channelId.value || undefined,
    })
  } catch (error) {
    allError.value = resolveErrorMessage(error, '搜索失败，请稍后重试')
    allResult.value = null
  } finally {
    allLoading.value = false
  }
}

function runSearch(): void {
  if (!keyword.value) {
    allResult.value = null
    return
  }
  if (activeTab.value === 'all') {
    void searchAllResults()
    return
  }
  if (activeTab.value === 'channel') {
    void channelList.refresh()
  } else {
    void postList.refresh()
  }
}

watch([keyword, activeTab], runSearch, { immediate: true })

watch(
  () => route.query.type,
  (type) => {
    const tab = normalizeTab(type)
    if (tab !== activeTab.value) {
      activeTab.value = tab
    }
  },
)

function handleSubmit(query: string): void {
  if (keyword.value === query) {
    runSearch()
    return
  }
  void router.push({
    name: 'search',
    query: {
      q: query,
      ...(activeTab.value === 'all' ? {} : { type: activeTab.value }),
      ...(scope.value === 'channel'
        ? { scope: 'channel', channelId: channelId.value }
        : {}),
    },
  })
}

function setTab(tab: SearchTab): void {
  if (activeTab.value === tab) return
  activeTab.value = tab
  void router.replace({
    name: 'search',
    query: {
      q: keyword.value,
      ...(tab === 'all' ? {} : { type: tab }),
      ...(scope.value === 'channel'
        ? { scope: 'channel', channelId: channelId.value }
        : {}),
    },
  })
}

watch([channelSort, postSort], () => {
  runSearch()
})

watch(
  channelId,
  async (id) => {
    if (!id || scope.value !== 'channel') {
      channelName.value = ''
      return
    }
    try {
      const channel = await channelsApi.getChannel(id)
      channelName.value = channel.name
    } catch {
      channelName.value = ''
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="page search-page">
    <header class="page-header">
      <div>
        <h1 class="page-title">搜索</h1>
        <p class="page-subtitle">搜索频道与帖子，发现同频的岛民</p>
      </div>
    </header>

    <div class="search-page__panel isles-card">
      <SearchPanel
        :initial-keyword="keyword"
        :navigate-on-submit="false"
        :scope="scope"
        :channel-id="channelId"
        @submit="handleSubmit"
      />
    </div>

    <p v-if="scope === 'channel'" class="muted search-page__scope-hint">
      频道内搜索：{{ channelName || channelId }}，仅展示该频道的公开内容。
    </p>

    <template v-if="keyword">
      <nav class="search-tabs" aria-label="搜索结果类型">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          class="search-tab"
          :class="{ 'is-active': activeTab === tab.key }"
          @click="setTab(tab.key)"
        >
          {{ tab.label }}
        </button>
      </nav>

      <template v-if="activeTab === 'all'">
        <LoadingState v-if="allLoading" :rows="4" tip="正在搜索…" />

        <ErrorState v-else-if="allError" :message="allError" @retry="runSearch" />

        <EmptyState
          v-else-if="!hasAnyResult"
          title="没有找到相关内容"
          description="换个关键词试试，或前往频道动态浏览大家的新帖子。"
        />

        <div v-else class="search-sections">
          <section v-if="allResult && allResult.channels.length > 0" class="search-section">
            <h2 class="search-section__title">相关频道</h2>
            <div class="search-section__channels">
              <ChannelCard
                v-for="channel in allResult.channels"
                :key="channel.id"
                :channel="channel"
                layout="row"
              />
            </div>
          </section>

          <section v-if="allResult && allResult.posts.length > 0" class="search-section">
            <h2 class="search-section__title">相关帖子</h2>
            <div class="search-section__posts">
              <PostCard v-for="post in allResult.posts" :key="post.id" :post="post" show-channel />
            </div>
          </section>
        </div>
      </template>

      <template v-else-if="activeTab === 'channel'">
        <div class="search-sort">
          <span class="muted">排序：</span>
          <el-select v-model="channelSort" size="small" class="search-sort__select">
            <el-option v-for="option in channelSortOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </div>
        <LoadingState v-if="channelList.initialLoading.value" :rows="4" tip="正在搜索频道…" />
        <ErrorState
          v-else-if="channelList.error.value"
          :message="channelList.error.value"
          @retry="channelList.refresh()"
        />
        <EmptyState
          v-else-if="channelList.items.value.length === 0"
          title="没有找到相关频道"
          description="试试其他关键词，或返回频道动态。"
        />
        <div v-else class="search-section">
          <div class="search-section__channels">
            <ChannelCard
              v-for="channel in channelList.items.value"
              :key="channel.id"
              :channel="channel"
              layout="row"
            />
          </div>
          <div class="search-page__more">
            <el-button
              v-if="channelList.hasMore.value"
              text
              type="primary"
              :loading="channelList.loading.value"
              @click="channelList.loadMore()"
            >
              加载更多频道
            </el-button>
          </div>
        </div>
      </template>

      <template v-else>
        <div class="search-sort">
          <span class="muted">排序：</span>
          <el-select v-model="postSort" size="small" class="search-sort__select">
            <el-option v-for="option in postSortOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </div>
        <LoadingState v-if="postList.initialLoading.value" :rows="4" tip="正在搜索帖子…" />
        <ErrorState
          v-else-if="postList.error.value"
          :message="postList.error.value"
          @retry="postList.refresh()"
        />
        <EmptyState
          v-else-if="postList.items.value.length === 0"
          title="没有找到相关帖子"
          description="试试其他关键词，或返回频道动态。"
        />
        <div v-else class="search-section">
          <div class="search-section__posts">
            <PostCard v-for="post in postList.items.value" :key="post.id" :post="post" show-channel />
          </div>
          <div class="search-page__more">
            <el-button
              v-if="postList.hasMore.value"
              text
              type="primary"
              :loading="postList.loading.value"
              @click="postList.loadMore()"
            >
              加载更多帖子
            </el-button>
          </div>
        </div>
      </template>
    </template>

    <EmptyState
      v-else
      title="搜索群岛"
      description="输入关键词，搜索频道与帖子。支持历史记录，单条删除或一键清空。"
    />
  </div>
</template>

<style scoped lang="scss">
.search-page__panel {
  padding: var(--isles-space-4);
}

.search-page__scope-hint {
  margin-top: var(--isles-space-2);
  font-size: var(--isles-font-xs);
}

.search-tabs {
  display: flex;
  gap: var(--isles-space-1);
  margin: var(--isles-space-4) 0;
  padding: var(--isles-space-1);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-md);
  width: fit-content;
}

.search-tab {
  padding: 4px var(--isles-space-4);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-sm);
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

.search-sections {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-5);
}

.search-section__title {
  margin-bottom: var(--isles-space-3);
  font-size: var(--isles-font-md);
  font-weight: 600;
}

.search-section__channels {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
}

.search-section__posts {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-4);
}

.search-page__more {
  display: flex;
  justify-content: center;
  padding-top: var(--isles-space-3);
}

.search-sort {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  margin-bottom: var(--isles-space-3);
  font-size: var(--isles-font-xs);
}

.search-sort__select {
  width: 140px;
}
</style>
