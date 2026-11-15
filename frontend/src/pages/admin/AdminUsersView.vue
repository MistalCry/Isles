<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingState from '@/components/LoadingState.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import {
  banAdminUser,
  createUserPenalty,
  listAdminUsers,
  listUserPenalties,
  revokePenalty,
  unbanAdminUser,
  type AdminPenalty,
  type AdminUser,
  type AdminUserPage,
  type PenaltyType,
} from './adminApi'
import { resolveErrorMessage } from '@/api/client'
import { formatDateTime } from '@/utils/format'
import { hasPermission } from './adminPermissions'
import { useAdminAccess } from '@/composables/useAdminAccess'

const { t } = useI18n()
const { identity } = useAdminAccess()
const canBan = computed(() => hasPermission(identity.value, 'USER_BAN'))
const canPenalty = computed(() => hasPermission(identity.value, 'USER_PENALTY'))

const keyword = ref('')
const status = ref('')
const page = ref(1)
const size = ref(20)
const data = ref<AdminUserPage | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

const penaltyOpen = ref(false)
const penaltyUser = ref<AdminUser | null>(null)
const penalties = ref<AdminPenalty[]>([])
const penaltiesLoading = ref(false)
const penaltiesError = ref<string | null>(null)
const submitting = ref(false)
const form = ref<{ type: PenaltyType; duration: string; reason: string }>({
  type: 'WARN',
  duration: '24',
  reason: '',
})

/**
 * 常用时长。用字符串承载「永久」（'permanent'）：
 * el-select 的 value 不接受 null，提交时再转成 null（后端 endAt 为空）。
 */
const DURATION_OPTIONS = [
  { value: '24', labelKey: 'admin.penalty.duration24h' },
  { value: '72', labelKey: 'admin.penalty.duration3d' },
  { value: '168', labelKey: 'admin.penalty.duration7d' },
  { value: '720', labelKey: 'admin.penalty.duration30d' },
  { value: 'permanent', labelKey: 'admin.penalty.durationPermanent' },
]

const PENALTY_TYPE_KEYS: Record<PenaltyType, string> = {
  BAN: 'admin.penalty.typeBan',
  MUTE: 'admin.penalty.typeMute',
  WARN: 'admin.penalty.typeWarn',
}

function typeLabel(type?: string | null): string {
  const key = PENALTY_TYPE_KEYS[(type ?? 'WARN') as PenaltyType]
  return key ? t(key) : (type ?? '—')
}

function typeTagType(type?: string | null): 'danger' | 'warning' | 'info' {
  if (type === 'BAN') return 'danger'
  if (type === 'MUTE') return 'warning'
  return 'info'
}

function statusLabel(value?: string | null): string {
  if (value === 'REVOKED') return t('admin.penalty.statusRevoked')
  if (value === 'EXPIRED') return t('admin.penalty.statusExpired')
  return t('admin.penalty.statusActive')
}

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

async function openPenalties(user: AdminUser): Promise<void> {
  penaltyUser.value = user
  penaltyOpen.value = true
  form.value = { type: 'WARN', duration: '24', reason: '' }
  await loadPenalties()
}

async function loadPenalties(): Promise<void> {
  if (!penaltyUser.value) return
  penaltiesLoading.value = true
  penaltiesError.value = null
  try {
    penalties.value = await listUserPenalties(penaltyUser.value.id)
  } catch (cause) {
    penaltiesError.value = resolveErrorMessage(cause, t('admin.penalty.loadFailed'))
  } finally {
    penaltiesLoading.value = false
  }
}

async function submitPenalty(): Promise<void> {
  if (!penaltyUser.value) return
  if (!form.value.reason.trim()) {
    ElMessage.warning(t('admin.penalty.reasonRequired'))
    return
  }
  submitting.value = true
  try {
    await createUserPenalty(penaltyUser.value.id, {
      type: form.value.type,
      reason: form.value.reason.trim(),
      // 警告与「永久」都不需要具体时长（后端 endAt 为空）
      durationHours:
        form.value.type === 'WARN' || form.value.duration === 'permanent'
          ? null
          : Number(form.value.duration),
    })
    ElMessage.success(t('admin.penalty.created'))
    form.value = { type: 'WARN', duration: '24', reason: '' }
    await loadPenalties()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.penalty.failed')))
  } finally {
    submitting.value = false
  }
}

async function revoke(penalty: AdminPenalty): Promise<void> {
  try {
    await ElMessageBox.confirm(t('admin.penalty.revokeConfirm'), t('admin.penalty.revoke'), {
      type: 'warning',
      confirmButtonText: t('admin.penalty.revoke'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  try {
    await revokePenalty(penalty.id)
    ElMessage.success(t('admin.penalty.revoked'))
    await loadPenalties()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.penalty.failed')))
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
        <el-table-column :label="t('common.actions')" width="190" align="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" :disabled="!canPenalty" @click="openPenalties(row as AdminUser)">
              {{ t('admin.penalty.title') }}
            </el-button>
            <el-button
              size="small"
              link
              :type="row.status === 'BANNED' ? 'primary' : 'danger'"
              :disabled="!canBan"
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

    <el-drawer v-model="penaltyOpen" :title="t('admin.penalty.title')" size="560px" append-to-body>
      <div v-if="penaltyUser" class="penalty-user">
        <UserAvatar :src="penaltyUser.avatarUrl" :name="penaltyUser.nickname || penaltyUser.username" :size="40" />
        <div>
          <p class="penalty-user__name">{{ penaltyUser.nickname || penaltyUser.username }}</p>
          <p class="penalty-user__sub">@{{ penaltyUser.username }} · {{ penaltyUser.id }}</p>
        </div>
      </div>

      <el-form label-position="top" class="penalty-form">
        <el-form-item :label="t('admin.penalty.type')">
          <el-radio-group v-model="form.type">
            <el-radio value="WARN">{{ t('admin.penalty.typeWarn') }}</el-radio>
            <el-radio value="MUTE">{{ t('admin.penalty.typeMute') }}</el-radio>
            <el-radio value="BAN">{{ t('admin.penalty.typeBan') }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.type !== 'WARN'" :label="t('admin.penalty.duration')">
          <el-select v-model="form.duration" class="penalty-select">
            <el-option
              v-for="option in DURATION_OPTIONS"
              :key="String(option.value)"
              :label="t(option.labelKey)"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('admin.penalty.reason')" required>
          <el-input
            v-model="form.reason"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            :placeholder="t('admin.penalty.reasonPlaceholder')"
          />
        </el-form-item>
        <el-button type="primary" :loading="submitting" :disabled="!canPenalty" @click="submitPenalty">
          {{ t('admin.penalty.create') }}
        </el-button>
      </el-form>

      <h4 class="penalty-history-title">{{ t('admin.penalty.history') }}</h4>
      <el-skeleton v-if="penaltiesLoading && penalties.length === 0" :rows="3" animated />
      <ErrorState v-else-if="penaltiesError" :message="penaltiesError" @retry="loadPenalties" />
      <el-empty v-else-if="penalties.length === 0" :description="t('admin.penalty.historyEmpty')" />
      <ul v-else class="penalty-list">
        <li v-for="item in penalties" :key="item.id" class="penalty-item">
          <div class="penalty-item__head">
            <el-tag size="small" effect="plain" :type="typeTagType(item.type)">{{ typeLabel(item.type) }}</el-tag>
            <el-tag size="small" :type="item.status === 'ACTIVE' ? 'warning' : 'info'" effect="plain">
              {{ statusLabel(item.status) }}
            </el-tag>
            <span class="muted penalty-item__time">{{ formatDateTime(item.createdAt) }}</span>
          </div>
          <p class="penalty-item__reason">{{ item.reason }}</p>
          <p class="penalty-item__meta muted">
            {{ item.startAt ? formatDateTime(item.startAt) : '—' }}
            →
            {{ item.endAt ? formatDateTime(item.endAt) : t('admin.penalty.permanent') }}
          </p>
          <el-button
            v-if="item.status === 'ACTIVE'"
            size="small"
            link
            type="danger"
            :disabled="!canPenalty"
            @click="revoke(item)"
          >
            {{ t('admin.penalty.revoke') }}
          </el-button>
        </li>
      </ul>
    </el-drawer>
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

.penalty-user {
  display: flex;
  gap: var(--isles-space-3);
  align-items: center;
  padding-bottom: var(--isles-space-3);
  border-bottom: 1px solid var(--isles-border);
}

.penalty-user__name {
  font-weight: 600;
}

.penalty-user__sub {
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.penalty-form {
  margin-top: var(--isles-space-3);
}

.penalty-select {
  width: 100%;
}

.penalty-history-title {
  margin: var(--isles-space-4) 0 var(--isles-space-2);
  font-size: var(--isles-font-md);
  font-weight: 600;
}

.penalty-list {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-3);
}

.penalty-item {
  padding: var(--isles-space-3);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-md);
}

.penalty-item__head {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
}

.penalty-item__time {
  margin-left: auto;
  font-size: var(--isles-font-xs);
}

.penalty-item__reason {
  margin-top: var(--isles-space-2);
  font-size: var(--isles-font-sm);
}

.penalty-item__meta {
  margin-top: 2px;
  font-size: var(--isles-font-xs);
}
</style>
