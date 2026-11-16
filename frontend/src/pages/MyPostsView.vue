<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, Delete, EditPen, Star, View } from '@element-plus/icons-vue'
import { usersApi } from '@/api'
import { http, resolveErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useChannelStore } from '@/stores/channel'
import { excerpt, formatCount, formatRelativeTime } from '@/utils/format'
import type { Post } from '@/types/models'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const { t } = useI18n()

const router = useRouter()
const auth = useAuthStore()
const channelStore = useChannelStore()
const { myChannels } = storeToRefs(channelStore)

const items = ref<Post[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const page = ref(1)
const size = 20
const total = ref(0)
const channelId = ref('')
const deletingId = ref<string | null>(null)

const hasMore = computed(() => items.value.length < total.value)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const result = await usersApi.getMyPosts({
      page: page.value,
      size,
      channelId: channelId.value || undefined,
    })
    items.value = result.items
    total.value = result.total ?? result.items.length
  } catch (cause) {
    error.value = resolveErrorMessage(cause, t('myPosts.loadFailed'))
    items.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function openPost(post: Post): void {
  void router.push({ name: 'post', params: { id: post.id } })
}

function editPost(post: Post): void {
  void router.push({ name: 'post', params: { id: post.id }, query: { edit: '1' } })
}

/**
 * 删除自己的帖子：优先用作者自助删除 `DELETE /posts/{id}`；
 * 该端点还没上线时退回频道管理删除（仅对可管理自己频道的作者有效）。
 */
async function deletePost(post: Post): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    t('myPosts.deleteConfirm', { title: post.title || excerpt(post.content, 24) }),
    t('myPosts.delete'),
    { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') },
  ).catch(() => false)
  if (!confirmed) return
  deletingId.value = post.id
  try {
    try {
      await http.delete(`/posts/${encodeURIComponent(post.id)}`)
    } catch (cause) {
      const status = (cause as { status?: number }).status
      if (status !== 404 && status !== 405) throw cause
      await http.delete(
        `/channels/${encodeURIComponent(post.channelId)}/posts/${encodeURIComponent(post.id)}`,
      )
    }
    items.value = items.value.filter((item) => item.id !== post.id)
    total.value = Math.max(0, total.value - 1)
    ElMessage.success(t('myPosts.deleted'))
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('myPosts.deleteFailed')))
  } finally {
    deletingId.value = null
  }
}

function changePage(next: number): void {
  page.value = next
  void load()
}

watch(channelId, () => {
  page.value = 1
  void load()
})

onMounted(() => {
  if (auth.isAuthenticated && myChannels.value.length === 0) {
    void channelStore.fetchMyChannels()
  }
  void load()
})
</script>

<template>
  <div class="page my-posts-page">
    <header class="page-header">
      <div>
        <h1 class="page-title">{{ t('myPosts.title') }}</h1>
        <p class="page-subtitle">{{ t('myPosts.subtitle') }}</p>
      </div>
      <el-select v-model="channelId" class="my-posts-page__filter" :placeholder="t('myPosts.allChannels')">
        <el-option :label="t('myPosts.allChannels')" value="" />
        <el-option v-for="channel in myChannels" :key="channel.id" :label="channel.name" :value="channel.id" />
      </el-select>
    </header>

    <LoadingState v-if="loading" :rows="5" :tip="t('common.loading')" />

    <ErrorState v-else-if="error" :message="error" @retry="load" />

    <EmptyState
      v-else-if="items.length === 0"
      :title="channelId ? t('myPosts.emptyFiltered') : t('myPosts.empty')"
      :description="t('myPosts.emptyHint')"
    >
      <template #action>
        <el-button type="primary" @click="router.push({ name: 'feed' })">{{ t('nav.feed') }}</el-button>
      </template>
    </EmptyState>

    <ul v-else class="my-posts">
      <li v-for="post in items" :key="post.id" class="my-posts__item isles-card">
        <div class="my-posts__main">
          <button type="button" class="my-posts__title" @click="openPost(post)">
            {{ post.title || excerpt(post.content, 60) }}
          </button>
          <p v-if="post.title" class="my-posts__excerpt">{{ excerpt(post.content, 90) }}</p>
          <div class="my-posts__meta">
            <span v-if="post.channel" class="my-posts__channel">{{ post.channel.name }}</span>
            <time :datetime="post.createdAt">{{ formatRelativeTime(post.createdAt) }}</time>
            <span class="my-posts__stat"><el-icon :size="12"><Star /></el-icon>{{ formatCount(post.likeCount) }}</span>
            <span class="my-posts__stat"
              ><el-icon :size="12"><ChatDotRound /></el-icon>{{ formatCount(post.commentCount) }}</span
            >
            <span v-if="post.viewCount" class="my-posts__stat"
              ><el-icon :size="12"><View /></el-icon>{{ formatCount(post.viewCount) }}</span
            >
          </div>
        </div>
        <div class="my-posts__actions">
          <el-button text size="small" @click="openPost(post)">{{ t('myPosts.view') }}</el-button>
          <el-button text size="small" :icon="EditPen" @click="editPost(post)">{{ t('common.edit') }}</el-button>
          <el-button
            text
            size="small"
            type="danger"
            :icon="Delete"
            :loading="deletingId === post.id"
            @click="deletePost(post)"
          >
            {{ t('common.delete') }}
          </el-button>
        </div>
      </li>
    </ul>

    <footer v-if="total > size" class="my-posts__pager">
      <el-pagination
        layout="prev, pager, next"
        :current-page="page"
        :page-size="size"
        :total="total"
        background
        @current-change="changePage"
      />
      <span v-if="hasMore" class="muted">{{ t('myPosts.pagerHint', { n: total }) }}</span>
    </footer>
  </div>
</template>

<style scoped lang="scss">
.my-posts-page__filter {
  width: 200px;
}

.my-posts {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-3);
  padding: 0;
  margin: 0;
  list-style: none;
}

.my-posts__item {
  display: flex;
  gap: var(--isles-space-3);
  align-items: flex-start;
  justify-content: space-between;
  padding: var(--isles-space-3) var(--isles-space-4);
}

.my-posts__main {
  flex: 1;
  min-width: 0;
}

.my-posts__title {
  padding: 0;
  color: var(--isles-text);
  font-size: var(--isles-font-md);
  font-weight: 600;
  text-align: left;
  background: transparent;
  border: none;
  cursor: pointer;

  &:hover {
    color: var(--isles-primary);
  }
}

.my-posts__excerpt {
  margin-top: var(--isles-space-1);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
  display: -webkit-box;
  overflow: hidden;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.my-posts__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-3);
  align-items: center;
  margin-top: var(--isles-space-2);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.my-posts__channel {
  padding: 0 8px;
  color: var(--isles-primary);
  background: var(--isles-primary-soft);
  border-radius: var(--isles-radius-pill);
}

.my-posts__stat {
  display: inline-flex;
  gap: 2px;
  align-items: center;
}

.my-posts__actions {
  display: flex;
  flex: none;
  gap: 2px;
  align-items: center;
}

.my-posts__pager {
  display: flex;
  gap: var(--isles-space-3);
  align-items: center;
  justify-content: center;
  margin-top: var(--isles-space-4);
}
</style>
