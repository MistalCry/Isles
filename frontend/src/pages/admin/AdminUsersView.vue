<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingState from '@/components/LoadingState.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import {
  banAdminUser,
  listAdminUsers,
  unbanAdminUser,
  type AdminUser,
  type AdminUserPage,
} from './adminApi'
import { resolveErrorMessage } from '@/api/client'
import { formatDateTime } from '@/utils/format'

const { t } = useI18n()

const keyword = ref('')
const status = ref('')
const page = ref(1)
const size = ref(20)
const data = ref<AdminUserPage | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    data.value = await listAdminUsers({
      keyword: keyword.value.trim() || undefined,
      status: status.value || undefined,
      page: page.value,
      size: size.value,
    })
  } catch (cause) {
    error.value = resolveErrorMessage(cause, t('admin.users.loadFailed'))
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
  void load()
}

async function toggleStatus(user: AdminUser): Promise<void> {
  const banned = user.status === 'BANNED'
  const confirmed = await ElMessageBox.confirm(
    banned
      ? t('admin.users.unbanConfirm', { name: user.nickname || user.username })
      : t('admin.users.banConfirm', { name: user.nickname || user.username }),
    banned ? t('admin.users.enableTitle') : t('admin.users.disableTitle'),
    { type: 'warning' },
  ).catch(() => false)
  if (!confirmed) return
  try {
    if (banned) {
      await unbanAdminUser(user.id)
      ElMessage.success(t('admin.channels.unbanned'))
    } else {
      await banAdminUser(user.id)
      ElMessage.success(t('admin.users.disabled'))
    }
    await load()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.channels.actionFailed')))
  }
}

onMounted(load)
</script>

<template>
  <section class="admin-users">
    <header class="admin-section-head">
      <div>
        <h2 class="admin-section-title">{{ t('admin.layout.users') }}</h2>
        <p class="admin-section-subtitle">{{ t('admin.users.subtitle') }}</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('common.refresh') }}</el-button>
    </header>

    <div class="admin-filters">
      <el-input
        v-model="keyword"
        :placeholder="t('admin.users.search')"
        clearable
        class="admin-filters__keyword"
        @keydown.enter="search"
      />
      <el-select v-model="status" :placeholder="t('common.allStatus')" clearable class="admin-filters__select" @change="search">
        <el-option :label="t('admin.users.normal')" value="ACTIVE" />
        <el-option :label="t('admin.users.disabled')" value="BANNED" />
        <el-option :label="t('admin.aiModels.disabled')" value="DISABLED" />
      </el-select>
      <el-button type="primary" :icon="Search" @click="search">{{ t('common.query') }}</el-button>
    </div>

    <LoadingState v-if="loading && !data" :rows="3" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <EmptyState v-else-if="!data || data.items.length === 0" :title="t('admin.users.noMatch')" :description="t('common.retryHint')" />
    <template v-else>
      <el-table :data="data.items" class="admin-table" row-key="id">
        <el-table-column :label="t('avatar.user')" min-width="220">
          <template #default="{ row }">
            <div class="user-cell">
              <UserAvatar :src="row.avatarUrl" :name="row.nickname || row.username" :size="32" />
              <div class="user-cell__meta">
                <p class="user-cell__name">{{ row.nickname || row.username }}</p>
                <p class="user-cell__sub">@{{ row.username }}</p>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.status')" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'danger'" effect="plain">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('admin.users.role')" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.platformRole === 'ADMIN' ? 'warning' : 'info'" effect="plain">
              {{ row.platformRole }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('admin.users.registeredAt')" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="120" align="right">
          <template #default="{ row }">
            <el-button
              size="small"
              link
              :type="row.status === 'BANNED' ? 'primary' : 'danger'"
              @click="toggleStatus(row as AdminUser)"
            >
              {{ row.status === 'BANNED' ? t('admin.channels.unban') : t('admin.users.disable') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
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

.admin-filters__keyword {
  width: 240px;
}

.admin-filters__select {
  width: 140px;
}

.admin-table {
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
}

.user-cell {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
}

.user-cell__meta {
  min-width: 0;
  line-height: 1.3;
}

.user-cell__name {
  overflow: hidden;
  font-size: var(--isles-font-sm);
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-cell__sub {
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
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
