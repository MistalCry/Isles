<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { channelsApi, manageApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import {
  CHANNEL_PERMISSIONS,
  hasChannelPermission,
  resolveChannelPermissions,
} from '@/utils/channelPermissions'
import { excerpt, formatCount, formatDateTime } from '@/utils/format'
import type { Board, Channel, Post } from '@/types/models'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const { t } = useI18n()

const props = defineProps<{
  channel: Channel
  channelId: string
}>()

const auth = useAuthStore()
const filters = reactive({
  keyword: '',
  boardId: '',
  status: '',
  dateRange: null as [string, string] | null,
})
const posts = ref<Post[]>([])
const boards = ref<Board[]>([])
const selectedIds = ref<string[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const total = ref(0)
const page = ref(1)
const pageSize = 10
const batchLoading = ref(false)

const permissions = computed(() => resolveChannelPermissions(props.channel, auth.currentUser?.id))
const canPin = computed(() => hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.POST_PIN))
const canEssence = computed(() => hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.POST_ESSENCE))
const canDelete = computed(() => hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.POST_DELETE))

async function load(reset = false): Promise<void> {
  if (reset) page.value = 1
  loading.value = true
  error.value = null
  try {
    const result = await manageApi.getContentPosts(props.channelId, {
      keyword: filters.keyword.trim() || undefined,
      boardId: filters.boardId || undefined,
      status: filters.status || undefined,
      from: filters.dateRange?.[0],
      to: filters.dateRange?.[1],
      page: page.value,
      size: pageSize,
    })
    posts.value = result.items
    total.value = result.total ?? result.items.length
  } catch (err) {
    error.value = resolveErrorMessage(err, t('manage.content.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function loadBoards(): Promise<void> {
  try {
    boards.value = await channelsApi.getBoards(props.channelId)
  } catch {
    boards.value = []
  }
}

function resetFilters(): void {
  filters.keyword = ''
  filters.boardId = ''
  filters.status = ''
  filters.dateRange = null
  void load(true)
}

function handleSelectionChange(rows: unknown[]): void {
  selectedIds.value = (rows as Post[]).map((row) => row.id)
}

function handlePageChange(value: number): void {
  page.value = value
  void load()
}

async function togglePin(raw: unknown): Promise<void> {
  const post = raw as Post
  try {
    await manageApi.pinPost(props.channelId, post.id, !post.isPinned)
    post.isPinned = !post.isPinned
    ElMessage.success(post.isPinned ? t('manage.content.pinned') : t('manage.content.unpinned'))
  } catch {
    // 拦截器已提示
  }
}

async function toggleEssence(raw: unknown): Promise<void> {
  const post = raw as Post
  try {
    await manageApi.essencePost(props.channelId, post.id, !post.isEssence)
    post.isEssence = !post.isEssence
    ElMessage.success(post.isEssence ? t('manage.content.featured') : t('manage.content.unfeatured'))
  } catch {
    // 拦截器已提示
  }
}

async function removePost(raw: unknown): Promise<void> {
  const post = raw as Post
  try {
    await ElMessageBox.confirm(t('manage.content.deleteConfirm'), t('manage.content.deleteTitle'), {
      type: 'warning',
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  try {
    await manageApi.deletePost(props.channelId, post.id)
    posts.value = posts.value.filter((item) => item.id !== post.id)
    total.value = Math.max(0, total.value - 1)
    ElMessage.success(t('manage.content.deleted'))
  } catch {
    // 拦截器已提示
  }
}

async function runBatch(action: 'DELETE' | 'PIN' | 'UNPIN' | 'ESSENCE' | 'UNESSENCE'): Promise<void> {
  if (selectedIds.value.length === 0) {
    ElMessage.warning(t('manage.content.selectFirst'))
    return
  }
  if (action === 'DELETE') {
    try {
      await ElMessageBox.confirm(
        t('manage.content.deleteSelected', { n: selectedIds.value.length }),
        t('manage.content.bulkDelete'),
        {
        type: 'warning',
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel'),
      })
    } catch {
      return
    }
  }
  batchLoading.value = true
  try {
    await manageApi.batchPostAction(props.channelId, { action, postIds: selectedIds.value })
    ElMessage.success(t('manage.content.bulkDone'))
    selectedIds.value = []
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    batchLoading.value = false
  }
}

onMounted(() => {
  void load(true)
  void loadBoards()
})
</script>

<template>
  <section class="manage-section">
    <header class="manage-section__header">
      <div>
        <h2>{{ t('manage.nav.content') }}</h2>
        <p class="muted">{{ t('manage.content.subtitle') }}</p>
      </div>
      <el-button :icon="Refresh" @click="load(true)">{{ t('common.refresh') }}</el-button>
    </header>

    <div class="isles-card content-filters">
      <el-input
        v-model="filters.keyword"
        :placeholder="t('manage.content.search')"
        clearable
        class="content-filters__input"
        @keyup.enter="load(true)"
      />
      <el-select v-model="filters.boardId" :placeholder="t('channel.page.boards')" clearable class="content-filters__select">
        <el-option v-for="board in boards" :key="board.id" :label="board.name" :value="board.id" />
      </el-select>
      <el-select v-model="filters.status" :placeholder="t('common.status')" clearable class="content-filters__select">
        <el-option :label="t('manage.content.published')" value="PUBLISHED" />
        <el-option :label="t('admin.aiModels.deleted')" value="DELETED" />
        <el-option :label="t('manage.content.hidden')" value="HIDDEN" />
      </el-select>
      <el-date-picker
        v-model="filters.dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        :start-placeholder="t('common.startDate')"
        :end-placeholder="t('common.endDate')"
        class="content-filters__date"
      />
      <el-button type="primary" :icon="Search" @click="load(true)">{{ t('common.query') }}</el-button>
      <el-button @click="resetFilters">{{ t('common.reset') }}</el-button>
    </div>

    <div v-if="selectedIds.length > 0 || canPin || canEssence || canDelete" class="content-batch">
      <span class="muted">{{ t('manage.content.selectedCount', { n: selectedIds.length }) }}</span>
      <el-button v-if="canPin" size="small" :disabled="batchLoading || selectedIds.length === 0" @click="runBatch('PIN')">{{ t('manage.content.bulkPin') }}</el-button>
      <el-button v-if="canPin" size="small" :disabled="batchLoading || selectedIds.length === 0" @click="runBatch('UNPIN')">{{ t('manage.content.unpin') }}</el-button>
      <el-button v-if="canEssence" size="small" :disabled="batchLoading || selectedIds.length === 0" @click="runBatch('ESSENCE')">{{ t('manage.content.bulkFeature') }}</el-button>
      <el-button v-if="canEssence" size="small" :disabled="batchLoading || selectedIds.length === 0" @click="runBatch('UNESSENCE')">{{ t('manage.content.unfeature') }}</el-button>
      <el-button v-if="canDelete" size="small" type="danger" :disabled="batchLoading || selectedIds.length === 0" @click="runBatch('DELETE')">{{ t('manage.content.bulkDelete') }}</el-button>
    </div>

    <LoadingState v-if="loading && posts.length === 0" :rows="4" :tip="t('manage.content.loading')" />
    <ErrorState v-else-if="error && posts.length === 0" :message="error" @retry="load(true)" />
    <EmptyState v-else-if="posts.length === 0" :title="t('manage.content.noMatch')" :description="t('common.retryHint')" />

    <template v-else>
      <div class="isles-card content-table">
        <el-table :data="posts" row-key="id" @selection-change="handleSelectionChange">
          <el-table-column type="selection" width="44" />
          <el-table-column :label="t('permission.content')" min-width="260">
            <template #default="{ row }">
              <div class="content-cell">
                <RouterLink :to="{ name: 'post', params: { id: row.id } }" class="content-cell__title line-clamp-1">
                  {{ row.title || excerpt(row.content, 50) }}
                </RouterLink>
                <p class="muted content-cell__excerpt line-clamp-2">{{ excerpt(row.content, 120) }}</p>
                <span class="muted content-cell__meta">
                  {{ row.media?.length ? t('manage.content.mediaPrefix', { n: row.media.length }) : '' }}{{ row.channel?.name || channel.name }}
                </span>
              </div>
            </template>
          </el-table-column>
          <el-table-column :label="t('comment.authorBadge')" width="130">
            <template #default="{ row }">{{ row.author.nickname || row.author.username }}</template>
          </el-table-column>
          <el-table-column :label="t('channel.page.boards')" width="120">
            <template #default="{ row }">{{ row.board?.name || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('manage.content.interactions')" width="150">
            <template #default="{ row }">
              <span class="muted">
                {{
                  t('manage.content.viewsLikes', {
                    views: formatCount(row.viewCount),
                    likes: formatCount(row.likeCount),
                  })
                }}
              </span>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.time')" width="160">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column :label="t('manage.content.statusFlags')" width="130">
            <template #default="{ row }">
              <el-tag v-if="row.isPinned" size="small" type="danger" effect="plain">{{ t('post.pinned') }}</el-tag>
              <el-tag v-if="row.isEssence" size="small" type="warning" effect="plain" class="content-tag">{{ t('post.essence') }}</el-tag>
              <span v-if="!row.isPinned && !row.isEssence" class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.actions')" width="200" fixed="right">
            <template #default="{ row }">
              <el-button v-if="canPin" text size="small" @click="togglePin(row)">
                {{ row.isPinned ? t('manage.content.unpin') : t('post.pinned') }}
              </el-button>
              <el-button v-if="canEssence" text size="small" @click="toggleEssence(row)">
                {{ row.isEssence ? t('manage.content.unfeature') : t('post.feature') }}
              </el-button>
              <el-button v-if="canDelete" text size="small" type="danger" @click="removePost(row)">{{ t('common.delete') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="manage-pagination">
        <el-pagination
          :current-page="page"
          :page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="handlePageChange"
        />
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

.content-filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
  padding: var(--isles-space-3);
  margin-bottom: var(--isles-space-3);
}

.content-filters__input {
  width: 220px;
}

.content-filters__select {
  width: 140px;
}

.content-filters__date {
  width: 260px;
}

.content-batch {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
  padding: var(--isles-space-2) 0;
}

.content-table {
  padding: var(--isles-space-2);
}

.content-cell__title {
  color: var(--isles-text);
  font-size: var(--isles-font-sm);
  font-weight: 600;

  &:hover {
    color: var(--isles-primary);
  }
}

.content-cell__excerpt {
  margin-top: 2px;
  font-size: var(--isles-font-xs);
}

.content-cell__meta {
  font-size: var(--isles-font-xs);
}

.content-tag {
  margin-left: 4px;
}

.manage-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: var(--isles-space-3);
}
</style>
