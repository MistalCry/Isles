<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingState from '@/components/LoadingState.vue'
import {
  handleAdminReport,
  listAdminReports,
  type AdminReport,
  type AdminReportPage,
} from './adminApi'
import { resolveErrorMessage } from '@/api/client'
import { formatDateTime } from '@/utils/format'

const status = ref('')
const page = ref(1)
const size = ref(20)
const data = ref<AdminReportPage | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

const actionDialogVisible = ref(false)
const actingReport = ref<AdminReport | null>(null)
const action = ref<'DISMISS' | 'RESOLVE'>('RESOLVE')
const note = ref('')
const submitting = ref(false)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    data.value = await listAdminReports({ status: status.value || undefined, page: page.value, size: size.value })
  } catch (cause) {
    error.value = resolveErrorMessage(cause, '举报列表加载失败')
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
  note.value = ''
  actionDialogVisible.value = true
}

async function submitHandle(): Promise<void> {
  if (!actingReport.value) return
  submitting.value = true
  try {
    await handleAdminReport(actingReport.value.id, action.value, note.value.trim() || undefined)
    ElMessage.success('处置已提交')
    actionDialogVisible.value = false
    await load()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, '处置失败'))
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
        <h2 class="admin-section-title">举报处置</h2>
        <p class="admin-section-subtitle">处理社区举报（驳回 / 处置完成）；数据来自 reports 表（WP1 · V2 迁移）</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </header>

    <div class="admin-filters">
      <el-select v-model="status" placeholder="全部状态" clearable class="admin-filters__select" @change="search">
        <el-option label="待处理" value="PENDING" />
        <el-option label="已驳回" value="DISMISSED" />
        <el-option label="已处置" value="RESOLVED" />
      </el-select>
    </div>

    <el-alert
      v-if="data && !data.available"
      class="reports-dependency"
      type="warning"
      show-icon
      :closable="false"
      title="举报数据表尚未就绪"
      :description="`接口已按契约预留，等待依赖：${data.dependency ?? 'reports（V2 迁移）'}`"
    />

    <LoadingState v-if="loading && !data" :rows="3" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <EmptyState
      v-else-if="data && data.available && data.items.length === 0"
      title="暂无举报"
      description="当前筛选条件下没有待处理的举报。"
    />
    <template v-else-if="data && data.available">
      <el-table :data="data.items" class="admin-table" row-key="id">
        <el-table-column prop="id" label="举报 ID" width="200" show-overflow-tooltip />
        <el-table-column label="类型 / 目标" min-width="160">
          <template #default="{ row }">
            <p>{{ row.targetType || '—' }}</p>
            <p class="cell-sub">{{ row.targetId || '—' }}</p>
          </template>
        </el-table-column>
        <el-table-column label="原因 / 说明" min-width="240">
          <template #default="{ row }">
            <p>{{ row.reason || '—' }}</p>
            <p class="cell-sub line-clamp-2">{{ row.detail }}</p>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.status || 'PENDING' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openHandle(row as AdminReport)">处置</el-button>
          </template>
        </el-table-column>
      </el-table>
      <footer class="admin-pager">
        <span>共 {{ data.total }} 条</span>
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          layout="prev, pager, next"
          :total="data.total"
          @current-change="load"
        />
      </footer>
    </template>

    <el-dialog v-model="actionDialogVisible" title="举报处置" width="480px" append-to-body>
      <p class="dialog-hint">举报 ID：{{ actingReport?.id }}</p>
      <el-form label-position="top">
        <el-form-item label="处置动作">
          <el-radio-group v-model="action">
            <el-radio value="RESOLVE">处置完成</el-radio>
            <el-radio value="DISMISS">驳回举报</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注（选填）">
          <el-input v-model="note" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="actionDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitHandle">提交</el-button>
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

.dialog-hint {
  margin-bottom: var(--isles-space-3);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-sm);
}

.admin-pager {
  display: flex;
  gap: var(--isles-space-3);
  align-items: center;
  justify-content: space-between;
  margin-top: var(--isles-space-3);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}
</style>
