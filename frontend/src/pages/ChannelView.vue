<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { channelsApi } from '@/api'
import { AppError, resolveErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useChannelStore } from '@/stores/channel'
import { useCursorList } from '@/composables/useCursorList'
import { useInfiniteScroll } from '@/composables/useInfiniteScroll'
import { useRequireLogin } from '@/composables/useRequireLogin'
import { excerpt, formatCount, formatRelativeTime } from '@/utils/format'
import type { Board, Channel, Post, PostTab } from '@/types/models'
import ChannelHeader from '@/components/ChannelHeader.vue'
import PostCard from '@/components/PostCard.vue'
import PostComposer from '@/components/PostComposer.vue'
import SearchPanel from '@/components/SearchPanel.vue'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

type ChannelTab = 'latest' | 'hot' | 'essence' | 'boards'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const channelStore = useChannelStore()
const { isAuthenticated } = storeToRefs(auth)
const { requireLogin } = useRequireLogin()

const channelId = computed(() => String(route.params.id ?? ''))
const channel = ref<Channel | null>(null)
const boards = ref<Board[]>([])
const loadingChannel = ref(true)
const channelError = ref<string | null>(null)
const channelErrorStatus = ref(0)
const joining = ref(false)

const activeTab = ref<ChannelTab>('latest')
const selectedBoardId = ref('')
const pinnedExpanded = ref(false)
const searchOpen = ref(false)
const fullComposerOpen = ref(false)
const sentinel = ref<HTMLElement | null>(null)

const tabs: Array<{ key: ChannelTab; label: string }> = [
  { key: 'latest', label: '全部' },
  { key: 'hot', label: '热门' },
  { key: 'essence', label: '精华' },
  { key: 'boards', label: '板块' },
]

const joined = computed(
  () => Boolean(channel.value?.isJoined || channelStore.isJoined(channelId.value)),
)
const pinnedPosts = computed(() => postList.items.value.filter((post) => post.isPinned).slice(0, 20))
const pinnedCount = computed(() => pinnedPosts.value.length)
const visiblePinnedPosts = computed(() =>
  pinnedExpanded.value ? pinnedPosts.value : pinnedPosts.value.slice(0, 1),
)
const selectedBoardName = computed(
  () => boards.value.find((board) => board.id === selectedBoardId.value)?.name ?? '',
)

const postList = useCursorList<Post>((cursor) => {
  const tab: PostTab = activeTab.value === 'boards' ? 'latest' : activeTab.value
  return channelsApi.getChannelPosts(channelId.value, {
    tab,
    boardId: activeTab.value === 'boards' ? selectedBoardId.value || undefined : undefined,
    cursor,
    limit: 20,
  })
})

function canLoadMore(): boolean {
  return !postList.loading.value && postList.hasMore.value
}

useInfiniteScroll(
  sentinel,
  () => {
    void postList.loadMore()
  },
  canLoadMore,
)

async function loadBoards(): Promise<void> {
  try {
    boards.value = await channelsApi.getBoards(channelId.value)
  } catch {
    boards.value = []
  }
}

async function loadChannel(): Promise<void> {
  if (!channelId.value) return
  loadingChannel.value = true
  channelError.value = null
  channelErrorStatus.value = 0
  pinnedExpanded.value = false
  try {
    channel.value = await channelsApi.getChannel(channelId.value)
    if (channel.value.isJoined) {
      channelStore.upsertMyChannel(channel.value)
    }
    void loadBoards()
    await postList.refresh()
  } catch (err) {
    channel.value = null
    const status = err instanceof AppError ? err.status : 0
    channelErrorStatus.value = status
    channelError.value =
      status === 404 ? '频道不存在或不可见' : resolveErrorMessage(err, '频道加载失败，请稍后重试')
  } finally {
    loadingChannel.value = false
  }
}

function syncCurrentChannel(): void {
  if (!channel.value) {
    channelStore.clearCurrentChannel(channelId.value)
    return
  }
  if (joined.value) {
    channelStore.clearCurrentChannel(channelId.value)
  } else {
    channelStore.setCurrentChannel(channel.value)
  }
}

watch([channel, joined], syncCurrentChannel)
watch(channelId, () => {
  activeTab.value = 'latest'
  selectedBoardId.value = ''
  boards.value = []
  pinnedExpanded.value = false
  void loadChannel()
})
watch([activeTab, selectedBoardId], () => {
  if (!loadingChannel.value) {
    void postList.refresh()
  }
})

onMounted(() => {
  void loadChannel()
})

onBeforeUnmount(() => {
  channelStore.clearCurrentChannel(channelId.value)
})

async function handleJoin(): Promise<void> {
  if (!requireLogin('登录后可加入频道')) return
  if (joining.value) return
  joining.value = true
  try {
    const joinedChannel = await channelStore.joinChannel(channelId.value)
    const detail = await channelsApi.getChannel(channelId.value).catch(() => joinedChannel)
    const nextChannel: Channel = { ...detail, isJoined: true }
    channel.value = nextChannel
    channelStore.upsertMyChannel(nextChannel)
    channelStore.clearCurrentChannel(channelId.value)
    ElMessage.success(`已加入「${detail.name}」`)
    void channelStore.fetchMyChannels(true)
  } catch {
    // 错误提示由 axios 拦截器统一处理
  } finally {
    joining.value = false
  }
}

function handleMemberAction(): void {
  void handleJoin()
}

function selectTab(tab: ChannelTab): void {
  activeTab.value = tab
  if (tab !== 'boards') {
    selectedBoardId.value = ''
  }
}

function selectBoard(boardId: string): void {
  selectedBoardId.value = boardId
}

function handlePublished(): void {
  fullComposerOpen.value = false
  void postList.refresh()
}

function handleChannelSearch(keyword: string): void {
  searchOpen.value = false
  void router.push({ name: 'search', query: { q: keyword, channelId: channelId.value } })
}

function handleMessages(): void {
  ElMessage.info('频道消息与互动提醒将在 M2 上线')
}

function handleSettings(): void {
  void router.push({ name: 'manage', params: { channelId: channelId.value } })
}

function openPinnedPost(post: Post): void {
  void router.push({ name: 'post', params: { id: post.id } })
}

function pinnedTitle(post: Post): string {
  return post.title || excerpt(post.content, 40)
}
</script>

<template>
  <div class="page channel-page">
    <LoadingState v-if="loadingChannel" :rows="6" tip="正在加载频道…" />

    <ErrorState
      v-else-if="channelError"
      :message="channelError"
      retry-text="重新加载频道"
      @retry="loadChannel"
    >
      <template #action>
        <el-button @click="$router.push('/feed')">回到频道动态</el-button>
      </template>
    </ErrorState>

    <template v-else-if="channel">
      <div class="channel-layout">
        <section class="channel-main" aria-label="频道内容">
          <ChannelHeader
            :channel="channel"
            :joined="joined"
            :joining="joining"
            @join="handleMemberAction"
            @search="searchOpen = true"
            @messages="handleMessages"
            @settings="handleSettings"
          />

          <nav class="channel-tabs" aria-label="频道内容筛选">
            <button
              v-for="tab in tabs"
              :key="tab.key"
              type="button"
              class="channel-tab"
              :class="{ 'is-active': activeTab === tab.key }"
              @click="selectTab(tab.key)"
            >
              {{ tab.label }}
              <span v-if="tab.key === 'boards' && boards.length > 0" class="channel-tab__count">
                {{ boards.length }}
              </span>
            </button>
          </nav>

          <div v-if="activeTab === 'boards'" class="channel-board-filter">
            <button
              type="button"
              class="board-chip"
              :class="{ 'is-active': selectedBoardId === '' }"
              @click="selectBoard('')"
            >
              全部板块
            </button>
            <button
              v-for="board in boards"
              :key="board.id"
              type="button"
              class="board-chip"
              :class="{ 'is-active': selectedBoardId === board.id }"
              @click="selectBoard(board.id)"
            >
              {{ board.name }}
            </button>
            <p v-if="boards.length === 0" class="muted channel-board-filter__empty">
              该频道还没有配置板块，M2 起频道主可在管理中心创建板块。
            </p>
          </div>

          <section v-if="pinnedCount > 0" class="pinned-panel" aria-label="置顶帖子">
            <div class="pinned-panel__head">
              <span class="pinned-panel__badge">置顶</span>
              <button
                v-if="pinnedPosts[0]"
                type="button"
                class="pinned-panel__title line-clamp-1"
                @click="openPinnedPost(pinnedPosts[0])"
              >
                {{ pinnedTitle(pinnedPosts[0]) }}
              </button>
              <span class="muted pinned-panel__count">共 {{ pinnedCount }} 条</span>
              <button
                type="button"
                class="link pinned-panel__toggle"
                @click="pinnedExpanded = !pinnedExpanded"
              >
                {{ pinnedExpanded ? '收起' : '展开全部' }}
              </button>
            </div>
            <ul v-if="pinnedExpanded" class="pinned-panel__list">
              <li v-for="post in visiblePinnedPosts" :key="post.id">
                <button type="button" class="pinned-panel__item line-clamp-1" @click="openPinnedPost(post)">
                  {{ pinnedTitle(post) }}
                </button>
                <time class="muted" :datetime="post.createdAt">{{ formatRelativeTime(post.createdAt) }}</time>
              </li>
            </ul>
          </section>

          <LoadingState v-if="postList.initialLoading.value" :rows="4" tip="正在加载帖子…" />

          <ErrorState
            v-else-if="postList.error.value"
            :message="postList.error.value"
            @retry="postList.refresh()"
          />

          <EmptyState
            v-else-if="postList.items.value.length === 0"
            title="还没有帖子"
            :description="
              activeTab === 'boards' && selectedBoardName
                ? `「${selectedBoardName}」板块还没有内容，来发布第一条帖子吧。`
                : '成为第一个在频道里发帖的人吧。'
            "
          />

          <div v-else class="channel-posts">
            <PostCard
              v-for="post in postList.items.value"
              :key="post.id"
              :post="post"
              :show-channel="false"
              :show-board="true"
            />
            <div ref="sentinel" class="channel-posts__load-more">
              <el-button
                v-if="postList.hasMore.value"
                text
                type="primary"
                :loading="postList.loading.value"
                @click="postList.loadMore()"
              >
                加载更多帖子
              </el-button>
              <span v-else class="muted">已浏览完该频道的全部帖子</span>
            </div>
          </div>

          <div class="channel-composer">
            <PostComposer
              v-if="isAuthenticated && joined"
              mode="quick"
              :channel-id="channelId"
              :boards="boards"
              :default-board-id="selectedBoardId"
              @published="handlePublished"
              @request-full="fullComposerOpen = true"
            />
            <div v-else class="join-prompt">
              <div>
                <strong>{{ isAuthenticated ? '加入频道后即可发帖' : '登录后加入频道参与讨论' }}</strong>
                <p class="muted">
                  {{ isAuthenticated ? '加入后你就能在这里发帖、评论和点赞。' : '游客可浏览公开内容，互动需要登录。' }}
                </p>
              </div>
              <el-button type="primary" :loading="joining" @click="handleMemberAction">
                {{ isAuthenticated ? '加入频道' : '登录 / 注册' }}
              </el-button>
            </div>
          </div>
        </section>

        <aside class="channel-side" aria-label="频道信息">
          <div class="isles-card channel-about">
            <h2 class="channel-about__title">频道简介</h2>
            <p class="channel-about__description">{{ channel.description || '这个频道还没有填写简介。' }}</p>
            <dl class="channel-about__stats">
              <div>
                <dt>帖子</dt>
                <dd>{{ formatCount(channel.postCount) }}</dd>
              </div>
              <div>
                <dt>成员</dt>
                <dd>{{ formatCount(channel.memberCount) }}</dd>
              </div>
              <div>
                <dt>板块</dt>
                <dd>{{ formatCount(boards.length) }}</dd>
              </div>
            </dl>
            <el-button
              v-if="!joined"
              type="primary"
              class="channel-about__join"
              :loading="joining"
              @click="handleMemberAction"
            >
              加入频道
            </el-button>
            <el-tag v-else type="success" effect="plain" class="channel-about__joined">已加入</el-tag>
            <p class="muted channel-about__meta">
              {{ channel.channelNo ? `频道号 #${channel.channelNo}` : '频道号分配中' }}
            </p>
          </div>
        </aside>
      </div>

      <el-dialog v-model="searchOpen" title="频道内搜索" width="min(560px, 94vw)" append-to-body>
        <SearchPanel
          scope="channel"
          :channel-id="channelId"
          :navigate-on-submit="false"
          @submit="handleChannelSearch"
        />
      </el-dialog>

      <el-dialog v-model="fullComposerOpen" title="发布长文" width="min(680px, 96vw)" top="6vh" append-to-body>
        <PostComposer
          mode="full"
          :channel-id="channelId"
          :boards="boards"
          :default-board-id="selectedBoardId"
          @published="handlePublished"
          @cancel="fullComposerOpen = false"
        />
      </el-dialog>
    </template>
  </div>
</template>

<style scoped lang="scss">
.channel-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: var(--isles-space-5);
  align-items: start;
}

.channel-main {
  min-width: 0;
}

.channel-tabs {
  display: flex;
  gap: var(--isles-space-1);
  padding: var(--isles-space-1);
  margin-bottom: var(--isles-space-3);
  overflow-x: auto;
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-md);
}

.channel-tab {
  flex: none;
  padding: 6px var(--isles-space-4);
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

.channel-tab__count {
  margin-left: 4px;
  font-size: var(--isles-font-xs);
  opacity: 0.7;
}

.channel-board-filter {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  margin-bottom: var(--isles-space-3);
}

.board-chip {
  padding: 3px 12px;
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-xs);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-pill);
  cursor: pointer;

  &:hover {
    border-color: var(--isles-border-strong);
    color: var(--isles-primary);
  }

  &.is-active {
    color: var(--isles-primary);
    background: var(--isles-primary-soft);
    border-color: var(--isles-primary-soft);
  }
}

.channel-board-filter__empty {
  width: 100%;
  font-size: var(--isles-font-xs);
}

.pinned-panel {
  margin-bottom: var(--isles-space-3);
  padding: var(--isles-space-3) var(--isles-space-4);
  background: var(--isles-accent-soft);
  border: 1px solid #f4d9c8;
  border-radius: var(--isles-radius-md);
}

.pinned-panel__head {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
}

.pinned-panel__badge {
  flex: none;
  padding: 0 8px;
  color: var(--isles-accent);
  font-size: var(--isles-font-xs);
  font-weight: 600;
  background: #fff;
  border-radius: var(--isles-radius-pill);
}

.pinned-panel__title {
  flex: 1;
  min-width: 120px;
  padding: 0;
  color: var(--isles-text);
  font-size: var(--isles-font-sm);
  font-weight: 600;
  text-align: left;
  background: transparent;
  border: none;
  cursor: pointer;

  &:hover {
    color: var(--isles-primary);
  }
}

.pinned-panel__count {
  font-size: var(--isles-font-xs);
}

.pinned-panel__toggle {
  padding: 0;
  font-size: var(--isles-font-xs);
  background: transparent;
  border: none;
}

.pinned-panel__list {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-1);
  margin-top: var(--isles-space-2);
  padding-top: var(--isles-space-2);
  border-top: 1px dashed #f0cfba;

  li {
    display: flex;
    gap: var(--isles-space-3);
    align-items: center;
    justify-content: space-between;
  }

  time {
    flex: none;
    font-size: var(--isles-font-xs);
  }
}

.pinned-panel__item {
  flex: 1;
  padding: 0;
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-xs);
  text-align: left;
  background: transparent;
  border: none;
  cursor: pointer;

  &:hover {
    color: var(--isles-primary);
  }
}

.channel-posts {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-4);
}

.channel-posts__load-more {
  display: flex;
  justify-content: center;
  padding: var(--isles-space-2) 0;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.channel-composer {
  position: sticky;
  bottom: var(--isles-space-4);
  z-index: 10;
  margin-top: var(--isles-space-4);
}

.join-prompt {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-3);
  align-items: center;
  justify-content: space-between;
  padding: var(--isles-space-4);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
  box-shadow: var(--isles-shadow-md);

  strong {
    font-size: var(--isles-font-sm);
  }

  p {
    margin-top: 2px;
    font-size: var(--isles-font-xs);
  }
}

.channel-about {
  position: sticky;
  top: var(--isles-space-5);
  padding: var(--isles-space-4);
}

.channel-about__title {
  font-size: var(--isles-font-sm);
  font-weight: 600;
}

.channel-about__description {
  margin-top: var(--isles-space-2);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-xs);
  line-height: var(--isles-line-base);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.channel-about__stats {
  display: flex;
  gap: var(--isles-space-4);
  margin-top: var(--isles-space-3);
  padding-top: var(--isles-space-3);
  border-top: 1px solid var(--isles-border);

  div {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  dt {
    color: var(--isles-text-muted);
    font-size: var(--isles-font-xs);
  }

  dd {
    margin: 0;
    font-size: var(--isles-font-md);
    font-weight: 600;
  }
}

.channel-about__join {
  width: 100%;
  margin-top: var(--isles-space-4);
}

.channel-about__joined {
  margin-top: var(--isles-space-4);
}

.channel-about__meta {
  margin-top: var(--isles-space-3);
  font-size: var(--isles-font-xs);
}

@media (max-width: 1023px) {
  .channel-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .channel-about {
    position: static;
  }

  .channel-side {
    order: 2;
  }
}

@media (max-width: 767px) {
  .channel-composer {
    bottom: var(--isles-space-2);
  }

  .channel-tab {
    padding: 6px var(--isles-space-3);
  }
}
</style>
