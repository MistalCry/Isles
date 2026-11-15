<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onMounted, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingState from '@/components/LoadingState.vue'
import { listAdminAuditLogs, type AdminAuditLog } from './adminApi'
import { resolveErrorMessage } from '@/api/client'
import { formatDateTime } from '@/utils/format'

const { t } = useI18n()

const action = ref('')
const actorId = ref('')
const range = ref<[string, string] | null>(null)
const page = ref(1)
const size = ref(20)
const items = ref<AdminAuditLog[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const data = await listAdminAuditLogs({
      action: action.value.trim() || undefined,
      actorId: actorId.value.trim() || undefined,
      from: range.value?.[0],
      to: range.value?.[1],
      page: page.value,
      size: size.value,
    })
    items.value = data.items
    total.value = data.total
  } catch (cause) {
    error.value = resolveErrorMessage(cause, t('admin.audit.loadFailed'))
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
  void load()
}

function reset(): void {
  action.value = ''
  actorId.value = ''
  range.value = null
  search()
}

onMounted(load)
</script>

<template>
  <section class="admin-audit">
    <header class="admin-section-head">
      <div>
        <h2 class="admin-section-title">{{ t('admin.layout.audit') }}</h2>
        <p class="admin-section-subtitle">{{ t('admin.audit.subtitle') }}</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('common.refresh') }}</el-button>
    </header>

    <div class="admin-filters">
      <el-input
        v-model="action"
        :placeholder="t('admin.audit.actionPlaceholder')"
        clearable
        class="admin-filters__search"
        @keyup.enter="search"
        @clear="search"
      />
      <el-input
        v-model="actorId"
        :placeholder="t('admin.audit.actorPlaceholder')"
        clearable
        class="admin-filters__search"
        @keyup.enter="search"
        @clear="search"
      />
      <el-date-picker
        v-model="range"
        type="datetimerange"
        value-format="YYYY-MM-DDTHH:mm:ss[Z]"
        :start-placeholder="t('common.startDate')"
        :end-placeholder="t('common.endDate')"
        class="admin-filters__range"
      />
      <el-button type="primary" :icon="Search" @click="search">{{ t('common.query') }}</el-button>
      <el-button @click="reset">{{ t('common.reset') }}</el-button>
    </div>

    <LoadingState v-if="loading && items.length === 0" :rows="4" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <EmptyState v-else-if="items.length === 0" :title="t('admin.audit.empty')" :description="t('admin.audit.emptyHint')" />
    <template v-else>
      <el-table :data="items" class="admin-table" row-key="id">
        <el-table-column :label="t('common.time')" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('admin.audit.actor')" width="150">
          <template #default="{ row }">
            <p>{{ row.actorName || row.actorId || '—' }}</p>
            <p v-if="row.actorName && row.actorId" class="cell-sub">{{ row.actorId }}</p>
          </template>
        </el-table-column>
        <el-table-column :label="t('admin.audit.action')" width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.action }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('admin.audit.target')" min-width="180">
          <template #default="{ row }">
            <p>{{ row.targetType || '—' }}</p>
            <p class="cell-sub">{{ row.targetId || '' }}</p>
          </template>
        </el-table-column>
        <el-table-column :label="t('admin.audit.detail')" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.detail || '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('admin.audit.ip')" width="140">
          <template #default="{ row }">{{ row.ip || '—' }}</template>
        </el-table-column>
      </el-table>
      <footer class="admin-pager">
        <span>{{ t('common.totalCount', { n: total }) }}</span>
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          layout="prev, pager, next"
          :total="total"
          @current-change="load"
        />
      </footer>
    </template>
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

.admin-filters__search {
  width: 200px;
}

.admin-filters__range {
  width: 380px;
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

@media (max-width: 900px) {
  .admin-filters__range {
    width: 100%;
  }
}
</style>
