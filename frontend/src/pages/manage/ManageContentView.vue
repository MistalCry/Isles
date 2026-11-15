<script setup lang="ts">
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
    error.value = resolveErrorMessage(err, '内容列表加载失败')
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
    ElMessage.success(post.isPinned ? '已置顶' : '已取消置顶')
  } catch {
    // 拦截器已提示
  }
}

async function toggleEssence(raw: unknown): Promise<void> {
  const post = raw as Post
  try {
    await manageApi.essencePost(props.channelId, post.id, !post.isEssence)
    post.isEssence = !post.isEssence
    ElMessage.success(post.isEssence ? '已加精' : '已取消精华')
  } catch {
    // 拦截器已提示
  }
}

async function removePost(raw: unknown): Promise<void> {
  const post = raw as Post
  try {
    await ElMessageBox.confirm('删除后帖子将进入系统消息通知作者，确认删除？', '删除帖子', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await manageApi.deletePost(props.channelId, post.id)
    posts.value = posts.value.filter((item) => item.id !== post.id)
    total.value = Math.max(0, total.value - 1)
    ElMessage.success('帖子已删除')
  } catch {
    // 拦截器已提示
  }
}

async function runBatch(action: 'DELETE' | 'PIN' | 'UNPIN' | 'ESSENCE' | 'UNESSENCE'): Promise<void> {
  if (selectedIds.value.length === 0) {
    ElMessage.warning('请先选择要操作的帖子')
    return
  }
  if (action === 'DELETE') {
    try {
      await ElMessageBox.confirm(`确认批量删除选中的 ${selectedIds.value.length} 条帖子？`, '批量删除', {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }
  batchLoading.value = true
  try {
    await manageApi.batchPostAction(props.channelId, { action, postIds: selectedIds.value })
    ElMessage.success('批量操作已完成')
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
        <h2>内容管理</h2>
        <p class="muted">筛选、置顶、精华、删除与批量操作（FR-MGR-05）</p>
      </div>
      <el-button :icon="Refresh" @click="load(true)">刷新</el-button>
    </header>

    <div class="isles-card content-filters">
      <el-input
        v-model="filters.keyword"
        placeholder="搜索标题 / 正文关键词"
        clearable
        class="content-filters__input"
        @keyup.enter="load(true)"
      />
      <el-select v-model="filters.boardId" placeholder="板块" clearable class="content-filters__select">
        <el-option v-for="board in boards" :key="board.id" :label="board.name" :value="board.id" />
      </el-select>
      <el-select v-model="filters.status" placeholder="状态" clearable class="content-filters__select">
        <el-option label="已发布" value="PUBLISHED" />
        <el-option label="已删除" value="DELETED" />
        <el-option label="已隐藏" value="HIDDEN" />
      </el-select>
      <el-date-picker
        v-model="filters.dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        class="content-filters__date"
      />
      <el-button type="primary" :icon="Search" @click="load(true)">查询</el-button>
      <el-button @click="resetFilters">重置</el-button>
    </div>

    <div v-if="selectedIds.length > 0 || canPin || canEssence || canDelete" class="content-batch">
      <span class="muted">已选择 {{ selectedIds.length }} 条</span>
      <el-button v-if="canPin" size="small" :disabled="batchLoading || selectedIds.length === 0" @click="runBatch('PIN')">批量置顶</el-button>
      <el-button v-if="canPin" size="small" :disabled="batchLoading || selectedIds.length === 0" @click="runBatch('UNPIN')">取消置顶</el-button>
      <el-button v-if="canEssence" size="small" :disabled="batchLoading || selectedIds.length === 0" @click="runBatch('ESSENCE')">批量加精</el-button>
      <el-button v-if="canEssence" size="small" :disabled="batchLoading || selectedIds.length === 0" @click="runBatch('UNESSENCE')">取消精华</el-button>
      <el-button v-if="canDelete" size="small" type="danger" :disabled="batchLoading || selectedIds.length === 0" @click="runBatch('DELETE')">批量删除</el-button>
    </div>

    <LoadingState v-if="loading && posts.length === 0" :rows="4" tip="正在加载内容…" />
    <ErrorState v-else-if="error && posts.length === 0" :message="error" @retry="load(true)" />
    <EmptyState v-else-if="posts.length === 0" title="没有匹配的内容" description="调整筛选条件后重试。" />

    <template v-else>
      <div class="isles-card content-table">
        <el-table :data="posts" row-key="id" @selection-change="handleSelectionChange">
          <el-table-column type="selection" width="44" />
          <el-table-column label="内容" min-width="260">
            <template #default="{ row }">
              <div class="content-cell">
                <RouterLink :to="{ name: 'post', params: { id: row.id } }" class="content-cell__title line-clamp-1">
                  {{ row.title || excerpt(row.content, 50) }}
                </RouterLink>
                <p class="muted content-cell__excerpt line-clamp-2">{{ excerpt(row.content, 120) }}</p>
                <span class="muted content-cell__meta">
                  {{ row.media?.length ? `${row.media.length} 个媒体 · ` : '' }}{{ row.channel?.name || channel.name }}
                </span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="作者" width="130">
            <template #default="{ row }">{{ row.author.nickname || row.author.username }}</template>
          </el-table-column>
          <el-table-column label="板块" width="120">
            <template #default="{ row }">{{ row.board?.name || '-' }}</template>
          </el-table-column>
          <el-table-column label="互动" width="150">
            <template #default="{ row }">
              <span class="muted">{{ formatCount(row.viewCount) }} 浏览 · {{ formatCount(row.likeCount) }} 赞</span>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="160">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="状态标识" width="130">
            <template #default="{ row }">
              <el-tag v-if="row.isPinned" size="small" type="danger" effect="plain">置顶</el-tag>
              <el-tag v-if="row.isEssence" size="small" type="warning" effect="plain" class="content-tag">精华</el-tag>
              <span v-if="!row.isPinned && !row.isEssence" class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button v-if="canPin" text size="small" @click="togglePin(row)">
                {{ row.isPinned ? '取消置顶' : '置顶' }}
              </el-button>
              <el-button v-if="canEssence" text size="small" @click="toggleEssence(row)">
                {{ row.isEssence ? '取消精华' : '加精' }}
              </el-button>
              <el-button v-if="canDelete" text size="small" type="danger" @click="removePost(row)">删除</el-button>
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
