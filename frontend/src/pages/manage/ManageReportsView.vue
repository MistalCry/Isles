<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { manageApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { formatDateTime } from '@/utils/format'
import type { Channel, ReportHandleAction, ReportItem } from '@/types/models'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const props = defineProps<{
  channel: Channel
  channelId: string
}>()

const statusOptions = [
  { value: 'PENDING', label: '待处理' },
  { value: 'ACCEPTED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
]

const handleOptions: Array<{ value: ReportHandleAction; label: string }> = [
  { value: 'REJECT', label: '驳回举报' },
  { value: 'APPROVE', label: '通过举报' },
  { value: 'DELETE_CONTENT', label: '删除被举报内容' },
]

const reports = ref<ReportItem[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const status = ref('PENDING')
const page = ref(1)
const pageSize = 20
const total = ref(0)
const handling = ref(false)
const handleDialogOpen = ref(false)
const currentReport = ref<ReportItem | null>(null)
const handleForm = reactive({ action: 'REJECT' as ReportHandleAction, note: '' })

async function load(reset = false): Promise<void> {
  if (reset) page.value = 1
  loading.value = true
  error.value = null
  try {
    const result = await manageApi.getChannelReports(props.channelId, {
      status: status.value || undefined,
      page: page.value,
      size: pageSize,
    })
    reports.value = result.items
    total.value = result.total ?? result.items.length
  } catch (err) {
    error.value = resolveErrorMessage(err, '举报列表加载失败')
  } finally {
    loading.value = false
  }
}

function openHandle(report: ReportItem): void {
  currentReport.value = report
  handleForm.action = 'REJECT'
  handleForm.note = ''
  handleDialogOpen.value = true
}

async function submitHandle(): Promise<void> {
  if (!currentReport.value) return
  handling.value = true
  try {
    await manageApi.handleReport(props.channelId, currentReport.value.id, {
      action: handleForm.action,
      note: handleForm.note.trim() || undefined,
    })
    ElMessage.success('举报已处理')
    handleDialogOpen.value = false
    reports.value = reports.value.filter((item) => item.id !== currentReport.value?.id)
    total.value = Math.max(0, total.value - 1)
  } catch {
    // 拦截器已提示
  } finally {
    handling.value = false
  }
}

function statusLabel(value: string): string {
  return statusOptions.find((item) => item.value === value)?.label ?? value
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
        <h2>举报处理</h2>
        <p class="muted">频道内帖子/评论举报的处置（FR-ITR-03）</p>
      </div>
      <div class="manage-section__actions">
        <el-select v-model="status" class="report-status" @change="load(true)">
          <el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-button :icon="Refresh" @click="load(true)">刷新</el-button>
      </div>
    </header>

    <LoadingState v-if="loading && reports.length === 0" :rows="3" tip="正在加载举报…" />
    <ErrorState v-else-if="error && reports.length === 0" :message="error" @retry="load(true)" />
    <EmptyState v-else-if="reports.length === 0" title="暂无待处理举报" description="频道内的举报会显示在这里。" />

    <div v-else class="report-list">
      <article v-for="report in reports" :key="report.id" class="report-card isles-card">
        <div class="report-card__main">
          <div class="report-card__head">
            <el-tag size="small" type="danger" effect="plain">{{ report.reason }}</el-tag>
            <el-tag size="small" type="info" effect="plain">{{ report.targetType }}</el-tag>
            <el-tag size="small" effect="plain">{{ statusLabel(report.status) }}</el-tag>
            <span class="muted">{{ formatDateTime(report.createdAt) }}</span>
          </div>
          <p class="report-card__desc">{{ report.description || '（无补充说明）' }}</p>
          <p class="muted report-card__meta">
            举报人：{{ report.reporterNickname || report.reporterId || '匿名' }} ·
            目标：{{ report.targetTitle || report.targetId }}
          </p>
        </div>
        <div class="report-card__actions">
          <RouterLink
            v-if="report.targetType === 'POST' && report.targetId"
            :to="{ name: 'post', params: { id: report.targetId } }"
            class="link"
          >
            查看内容
          </RouterLink>
          <el-button v-if="report.status === 'PENDING'" size="small" type="primary" @click="openHandle(report)">
            处理
          </el-button>
        </div>
      </article>
    </div>

    <div v-if="total > pageSize" class="manage-pagination">
      <el-pagination
        :current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="handlePageChange"
      />
    </div>

    <el-dialog v-model="handleDialogOpen" title="处理举报" width="440px" append-to-body>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="处理方式">
          <el-select v-model="handleForm.action">
            <el-option v-for="option in handleOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="处理备注">
          <el-input v-model="handleForm.note" type="textarea" :rows="3" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handleDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="handling" @click="submitHandle">提交处理</el-button>
      </template>
    </el-dialog>
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

.manage-section__actions {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
}

.report-status {
  width: 130px;
}

.report-list {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
}

.report-card {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-3);
  align-items: flex-start;
  justify-content: space-between;
  padding: var(--isles-space-4);
}

.report-card__main {
  flex: 1;
  min-width: 240px;
}

.report-card__head {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
  font-size: var(--isles-font-xs);
}

.report-card__desc {
  margin-top: var(--isles-space-2);
  font-size: var(--isles-font-sm);
}

.report-card__meta {
  margin-top: var(--isles-space-1);
  font-size: var(--isles-font-xs);
}

.report-card__actions {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
}

.manage-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: var(--isles-space-3);
}
</style>
