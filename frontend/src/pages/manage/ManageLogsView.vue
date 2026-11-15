<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { manageApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { formatDateTime } from '@/utils/format'
import type { AuditLogItem, Channel } from '@/types/models'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const props = defineProps<{
  channel: Channel
  channelId: string
}>()

const actionOptions = [
  { value: 'POST_CREATE', label: '发帖' },
  { value: 'POST_DELETE', label: '删除帖子' },
  { value: 'POST_PIN', label: '置顶' },
  { value: 'POST_UNPIN', label: '取消置顶' },
  { value: 'POST_ESSENCE', label: '加精' },
  { value: 'POST_UNESSENCE', label: '取消精华' },
  { value: 'COMMENT_DELETE', label: '删除评论' },
  { value: 'MEMBER_JOIN', label: '成员加入' },
  { value: 'MEMBER_LEAVE', label: '成员退出' },
  { value: 'MEMBER_REMOVE', label: '移除成员' },
  { value: 'MEMBER_MUTE', label: '禁言成员' },
  { value: 'MEMBER_UNMUTE', label: '解除禁言' },
  { value: 'MUTE_ALL', label: '全员禁言' },
  { value: 'ROLE_UPDATE', label: '身份组变更' },
  { value: 'PROFILE_UPDATE', label: '频道资料修改' },
  { value: 'JOIN_APPROVE', label: '通过加入申请' },
  { value: 'JOIN_REJECT', label: '拒绝加入申请' },
]

const filters = reactive({
  actorId: '',
  action: '',
  dateRange: null as [string, string] | null,
})
const logs = ref<AuditLogItem[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const total = ref(0)
const page = ref(1)
const pageSize = 20

function actionLabel(action: string): string {
  return actionOptions.find((item) => item.value === action)?.label ?? action
}

async function load(reset = false): Promise<void> {
  if (reset) page.value = 1
  loading.value = true
  error.value = null
  try {
    const result = await manageApi.getManageLogs(props.channelId, {
      actorId: filters.actorId.trim() || undefined,
      action: filters.action || undefined,
      from: filters.dateRange?.[0],
      to: filters.dateRange?.[1],
      page: page.value,
      size: pageSize,
    })
    logs.value = result.items
    total.value = result.total ?? result.items.length
  } catch (err) {
    error.value = resolveErrorMessage(err, '管理日志加载失败')
  } finally {
    loading.value = false
  }
}

function resetFilters(): void {
  filters.actorId = ''
  filters.action = ''
  filters.dateRange = null
  void load(true)
}

function handlePageChange(value: number): void {
  page.value = value
  void load()
}

onMounted(() => {
  void load(true)
})
</script>

<template>
  <section class="manage-section">
    <header class="manage-section__header">
      <div>
        <h2>管理日志</h2>
        <p class="muted">频道内全部写操作与管理操作审计（docs/03 §4.14 FR-MGR-07）</p>
      </div>
      <el-button :icon="Refresh" @click="load(true)">刷新</el-button>
    </header>

    <div class="isles-card log-filters">
      <el-input v-model="filters.actorId" placeholder="操作人用户 ID" clearable class="log-filters__input" />
      <el-select v-model="filters.action" placeholder="操作类型" clearable class="log-filters__select">
        <el-option v-for="option in actionOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-date-picker
        v-model="filters.dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        class="log-filters__date"
      />
      <el-button type="primary" :icon="Search" @click="load(true)">查询</el-button>
      <el-button @click="resetFilters">重置</el-button>
    </div>

    <LoadingState v-if="loading && logs.length === 0" :rows="4" tip="正在加载管理日志…" />
    <ErrorState v-else-if="error && logs.length === 0" :message="error" @retry="load(true)" />
    <EmptyState v-else-if="logs.length === 0" title="暂无管理日志" description="频道内的操作记录会显示在这里。" />

    <template v-else>
      <div class="isles-card log-table">
        <el-table :data="logs" style="width: 100%">
          <el-table-column prop="actorNickname" label="操作人" min-width="120">
            <template #default="{ row }">
              {{ row.actorNickname || row.actorId || '系统' }}
            </template>
          </el-table-column>
          <el-table-column prop="action" label="操作" min-width="120">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ actionLabel(row.action) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="targetId" label="对象" min-width="140">
            <template #default="{ row }">
              <span class="muted">{{ row.targetType || '-' }} {{ row.targetId || '' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="detail" label="详情" min-width="200">
            <template #default="{ row }">
              <span class="log-detail">{{ row.detail || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="ip" label="IP" width="130" />
          <el-table-column prop="createdAt" label="时间" width="170">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
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

.log-filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
  padding: var(--isles-space-3);
  margin-bottom: var(--isles-space-3);
}

.log-filters__input {
  width: 200px;
}

.log-filters__select {
  width: 170px;
}

.log-filters__date {
  width: 260px;
}

.log-table {
  padding: var(--isles-space-2);
}

.log-detail {
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-xs);
  overflow-wrap: anywhere;
}

.manage-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: var(--isles-space-3);
}
</style>
