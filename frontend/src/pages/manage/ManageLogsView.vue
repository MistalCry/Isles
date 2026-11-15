<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onMounted, reactive, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { manageApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { formatDateTime } from '@/utils/format'
import type { AuditLogItem, Channel } from '@/types/models'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const { t } = useI18n()

const props = defineProps<{
  channel: Channel
  channelId: string
}>()

const actionOptions = [
  { value: 'POST_CREATE', label: t('manage.logs.actionPostCreate') },
  { value: 'POST_DELETE', label: t('manage.content.deleteTitle') },
  { value: 'POST_PIN', label: t('post.pinned') },
  { value: 'POST_UNPIN', label: t('manage.content.unpin') },
  { value: 'POST_ESSENCE', label: t('post.feature') },
  { value: 'POST_UNESSENCE', label: t('manage.content.unfeature') },
  { value: 'COMMENT_DELETE', label: t('comment.deleteTitle') },
  { value: 'MEMBER_JOIN', label: t('manage.logs.actionMemberJoin') },
  { value: 'MEMBER_LEAVE', label: t('manage.logs.actionMemberLeave') },
  { value: 'MEMBER_REMOVE', label: t('channel.member.remove') },
  { value: 'MEMBER_MUTE', label: t('manage.logs.actionMute') },
  { value: 'MEMBER_UNMUTE', label: t('channel.member.unmute') },
  { value: 'MUTE_ALL', label: t('channel.settings.tabMuteAll') },
  { value: 'ROLE_UPDATE', label: t('manage.logs.actionRoleChange') },
  { value: 'PROFILE_UPDATE', label: t('manage.logs.actionProfileUpdate') },
  { value: 'JOIN_APPROVE', label: t('manage.logs.actionJoinApprove') },
  { value: 'JOIN_REJECT', label: t('manage.logs.actionJoinReject') },
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
    error.value = resolveErrorMessage(err, t('manage.logs.loadFailed'))
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
        <h2>{{ t('manage.nav.logs') }}</h2>
        <p class="muted">{{ t('manage.logs.subtitle') }}</p>
      </div>
      <el-button :icon="Refresh" @click="load(true)">{{ t('common.refresh') }}</el-button>
    </header>

    <div class="isles-card log-filters">
      <el-input v-model="filters.actorId" :placeholder="t('manage.logs.operatorId')" clearable class="log-filters__input" />
      <el-select v-model="filters.action" :placeholder="t('manage.logs.actionType')" clearable class="log-filters__select">
        <el-option v-for="option in actionOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-date-picker
        v-model="filters.dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        :start-placeholder="t('common.startDate')"
        :end-placeholder="t('common.endDate')"
        class="log-filters__date"
      />
      <el-button type="primary" :icon="Search" @click="load(true)">{{ t('common.query') }}</el-button>
      <el-button @click="resetFilters">{{ t('common.reset') }}</el-button>
    </div>

    <LoadingState v-if="loading && logs.length === 0" :rows="4" :tip="t('manage.logs.loading')" />
    <ErrorState v-else-if="error && logs.length === 0" :message="error" @retry="load(true)" />
    <EmptyState v-else-if="logs.length === 0" :title="t('manage.logs.empty')" :description="t('manage.logs.emptyHint')" />

    <template v-else>
      <div class="isles-card log-table">
        <el-table :data="logs" style="width: 100%">
          <el-table-column prop="actorNickname" :label="t('manage.logs.operator')" min-width="120">
            <template #default="{ row }">
              {{ row.actorNickname || row.actorId || t('common.system') }}
            </template>
          </el-table-column>
          <el-table-column prop="action" :label="t('common.actions')" min-width="120">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ actionLabel(row.action) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="targetId" :label="t('manage.logs.target')" min-width="140">
            <template #default="{ row }">
              <span class="muted">{{ row.targetType || '-' }} {{ row.targetId || '' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="detail" :label="t('manage.logs.detail')" min-width="200">
            <template #default="{ row }">
              <span class="log-detail">{{ row.detail || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="ip" label="IP" width="130" />
          <el-table-column prop="createdAt" :label="t('common.time')" width="170">
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
