<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingState from '@/components/LoadingState.vue'
import {
  handleAdminReport,
  listAdminReports,
  type AdminReport,
  type AdminReportPage,
  type ReportStatus,
} from './adminApi'
import { resolveErrorMessage } from '@/api/client'
import { formatDateTime } from '@/utils/format'
import { hasPermission } from './adminPermissions'
import { reportReasonLabel } from '@/constants/app'
import { useAdminAccess } from '@/composables/useAdminAccess'

const { t } = useI18n()
const { identity } = useAdminAccess()
const canHandle = computed(() => hasPermission(identity.value, 'REPORT_HANDLE'))

/** 首次进入默认看「待处理」：管理员打开举报页就是要清待办 */
const status = ref<string>('PENDING')
const targetType = ref('')
const keyword = ref('')
const page = ref(1)
const size = ref(20)
const data = ref<AdminReportPage | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

const actionDialogVisible = ref(false)
const actingReport = ref<AdminReport | null>(null)
const action = ref<'DISMISS' | 'RESOLVE'>('RESOLVE')
const handleAction = ref<'NONE' | 'DELETE_CONTENT' | 'MUTE_AUTHOR' | 'WARN'>('NONE')
const note = ref('')
const submitting = ref(false)

/** 后端已统一为 ACCEPTED/REJECTED，这里不再出现 DISMISSED/RESOLVED */
const STATUS_LABEL_KEYS: Record<ReportStatus, string> = {
  PENDING: 'admin.reportStatus.pending',
  ACCEPTED: 'admin.reportStatus.accepted',
  REJECTED: 'admin.reportStatus.rejected',
}

function statusLabel(value?: string | null): string {
  const key = STATUS_LABEL_KEYS[(value ?? 'PENDING') as ReportStatus]
  return key ? t(key) : (value ?? '')
}

/** 处置动作与目标类型也走中文映射，避免表格里出现裸枚举值 */
const HANDLE_ACTION_LABEL_KEYS: Record<string, string> = {
  NONE: 'admin.reports.handleNone',
  DELETE_CONTENT: 'admin.reports.handleDelete',
  MUTE_AUTHOR: 'admin.reports.handleMute',
  WARN: 'admin.reports.handleWarn',
}

function handleActionLabel(value?: string | null): string {
  if (!value) return '—'
  const key = HANDLE_ACTION_LABEL_KEYS[value]
  return key ? t(key) : value
}

function targetTypeLabel(value?: string | null): string {
  if (value === 'POST') return t('admin.reports.targetPost')
  if (value === 'COMMENT') return t('admin.reports.targetComment')
  return value || '—'
}

function statusTagType(value?: string | null): 'warning' | 'success' | 'info' {
  if (value === 'ACCEPTED') return 'success'
  if (value === 'REJECTED') return 'info'
  return 'warning'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    data.value = await listAdminReports({
      status: status.value || undefined,
      targetType: targetType.value || undefined,
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: size.value,
    })
  } catch (cause) {
    error.value = resolveErrorMessage(cause, t('manage.reports.loadFailed'))
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
  void load()
}

function openHandle(report: AdminReport): void {
  actingReport.value = report
  action.value = 'RESOLVE'
  handleAction.value = 'NONE'
  note.value = ''
  actionDialogVisible.value = true
}

async function submitHandle(): Promise<void> {
  if (!actingReport.value) return
  submitting.value = true
  try {
    await handleAdminReport(actingReport.value.id, action.value, note.value.trim() || undefined, handleAction.value)
    ElMessage.success(t('admin.reports.submitted'))
    actionDialogVisible.value = false
    await load()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.reports.failed')))
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="admin-reports">
    <header class="admin-section-head">
      <div>
        <h2 class="admin-section-title">{{ t('admin.layout.reports') }}</h2>
        <p class="admin-section-subtitle">{{ t('admin.reports.subtitle') }}</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('common.refresh') }}</el-button>
    </header>

    <div class="admin-filters">
      <el-input
        v-model="keyword"
        :placeholder="t('admin.reports.keywordPlaceholder')"
        :prefix-icon="Search"
        clearable
        class="admin-filters__search"
        @keyup.enter="search"
        @clear="search"
      />
      <el-select v-model="status" :placeholder="t('common.allStatus')" clearable class="admin-filters__select" @change="search">
        <el-option :label="t('common.all')" value="" />
        <el-option :label="t('admin.reportStatus.pending')" value="PENDING" />
        <el-option :label="t('admin.reportStatus.accepted')" value="ACCEPTED" />
        <el-option :label="t('admin.reportStatus.rejected')" value="REJECTED" />
      </el-select>
      <el-select
        v-model="targetType"
        :placeholder="t('admin.reports.targetType')"
        clearable
        class="admin-filters__select"
        @change="search"
      >
        <el-option :label="t('admin.reports.targetPost')" value="POST" />
        <el-option :label="t('admin.reports.targetComment')" value="COMMENT" />
      </el-select>
      <el-button type="primary" :icon="Search" @click="search">{{ t('common.query') }}</el-button>
    </div>

    <el-alert
      v-if="data && !data.available"
      class="reports-dependency"
      type="warning"
      show-icon
      :closable="false"
      :title="t('admin.reports.tableNotReady')"
      :description="
        t('admin.reports.reservedHint', { dep: data.dependency ?? t('admin.reports.source') })
      "
    />

    <LoadingState v-if="loading && !data" :rows="3" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <EmptyState
      v-else-if="data && data.items.length === 0"
      :title="status === 'PENDING' ? t('admin.reports.emptyPending') : t('admin.reports.empty')"
      :description="status === 'PENDING' ? t('admin.reports.emptyPendingHint') : t('admin.reports.emptyHint')"
    />
    <template v-else-if="data">
      <div class="admin-table-scroll">
      <el-table :data="data.items" class="admin-table" row-key="id">
        <el-table-column prop="id" :label="t('admin.reports.id')" width="200" show-overflow-tooltip />
        <el-table-column :label="t('admin.reports.target')" min-width="160">
          <template #default="{ row }">
            <p>{{ targetTypeLabel(row.targetType) }}</p>
            <p class="cell-sub">{{ row.targetId || '—' }}</p>
          </template>
        </el-table-column>
        <el-table-column :label="t('admin.reports.reason')" min-width="220">
          <template #default="{ row }">
            <p>{{ row.reason ? reportReasonLabel(row.reason) : '—' }}</p>
            <p class="cell-sub line-clamp-2">{{ row.detail }}</p>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.status')" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="statusTagType(row.status)">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('admin.reports.handler')" width="150">
          <template #default="{ row }">
            <template v-if="row.handledAt">
              <p class="cell-sub">{{ handleActionLabel(row.handleAction) }}</p>
              <p class="cell-sub">{{ formatDateTime(row.handledAt) }}</p>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.time')" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <!-- 钉在右侧：表格总宽超出容器时，处置入口不能被横向滚动藏起来 -->
        <el-table-column :label="t('common.actions')" width="120" align="right" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              link
              type="primary"
              :disabled="!canHandle || row.status !== 'PENDING'"
              @click="openHandle(row as AdminReport)"
            >
              {{ t('admin.reports.handle') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      </div>
      <footer class="admin-pager">
        <span>{{ t('common.totalCount', { n: data.total }) }}</span>
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          layout="prev, pager, next"
          :total="data.total"
          @current-change="load"
        />
      </footer>
    </template>

    <el-dialog v-model="actionDialogVisible" :title="t('admin.layout.reports')" width="480px" append-to-body>
      <p class="dialog-hint">{{ t('admin.reports.reportId', { id: actingReport?.id }) }}</p>
      <el-form label-position="top">
        <el-form-item :label="t('admin.reports.action')">
          <el-radio-group v-model="action">
            <el-radio value="RESOLVE">{{ t('admin.reportStatus.accepted') }}</el-radio>
            <el-radio value="DISMISS">{{ t('admin.reportStatus.rejected') }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('admin.reports.handleAction')">
          <el-select v-model="handleAction" class="dialog-select">
            <el-option :label="t('admin.reports.handleNone')" value="NONE" />
            <el-option :label="t('admin.reports.handleDelete')" value="DELETE_CONTENT" />
            <el-option :label="t('admin.reports.handleMute')" value="MUTE_AUTHOR" />
            <el-option :label="t('admin.reports.handleWarn')" value="WARN" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('admin.reports.note')">
          <el-input v-model="note" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="actionDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="submitHandle">{{ t('admin.reports.submit') }}</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped lang="scss">
.admin-section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: var(--isles-space-4);
}

.admin-section-title {
  font-size: var(--isles-font-lg);
  font-weight: 600;
}

.admin-section-subtitle {
  margin-top: var(--isles-space-1);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-sm);
}

.admin-filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  margin-bottom: var(--isles-space-3);
}

.admin-filters__select {
  width: 140px;
}

.admin-filters__search {
  width: 240px;
}

.reports-dependency {
  margin-bottom: var(--isles-space-3);
}

.admin-table {
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
}

.cell-sub {
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.admin-pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--isles-space-3);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-sm);
}

.dialog-hint {
  margin-bottom: var(--isles-space-3);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-sm);
}

.dialog-select {
  width: 100%;
}
</style>
