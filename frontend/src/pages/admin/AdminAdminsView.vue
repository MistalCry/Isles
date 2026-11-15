<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingState from '@/components/LoadingState.vue'
import {
  changeAdminRole,
  grantAdminAccount,
  listAdminAccounts,
  listAdminUsers,
  revokeAdminAccount,
  type AdminAccount,
  type AdminRole,
  type AdminUser,
} from './adminApi'
import { ADMIN_ROLE_LABEL_KEYS, ADMIN_ROLES } from './adminPermissions'
import { useAdminAccess } from '@/composables/useAdminAccess'
import { resolveErrorMessage } from '@/api/client'
import { formatDateTime } from '@/utils/format'

const { t } = useI18n()
const { identity } = useAdminAccess()

const items = ref<AdminAccount[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

const dialogVisible = ref(false)
const editing = ref<AdminAccount | null>(null)
const form = ref<{ userId: string; role: AdminRole; expiresAt: string | null }>({
  userId: '',
  role: 'MODERATOR',
  expiresAt: null,
})
const submitting = ref(false)

/** 授权弹窗的用户候选（远程搜索，排除已是管理员的账号） */
const userOptions = ref<AdminUser[]>([])
const userSearching = ref(false)
/** 编辑模式下把当前账号塞进候选，保证下拉能显示名字 */
const editingUser = ref<AdminUser | null>(null)

const existingAdminIds = computed(() => new Set(items.value.map((item) => item.userId)))

/** 昵称 + 用户名 + ID 后 6 位：ULID 太长，只留尾段便于核对 */
function userOptionLabel(user: AdminUser): string {
  const name = user.nickname || user.username
  return `${name} · @${user.username} · …${user.id.slice(-6)}`
}

async function searchUsers(keyword: string): Promise<void> {
  userSearching.value = true
  try {
    const page = await listAdminUsers({ keyword: keyword.trim() || undefined, page: 1, size: 20 })
    userOptions.value = page.items
  } catch {
    // 搜索失败不阻塞授权弹窗，保持上一次结果
    userOptions.value = []
  } finally {
    userSearching.value = false
  }
}

const dialogTitle = computed(() =>
  editing.value ? t('admin.admins.changeRole') : t('admin.admins.grant'),
)

function roleLabel(role: string): string {
  const key = ADMIN_ROLE_LABEL_KEYS[role as AdminRole]
  return key ? t(key) : role
}

/** 不能撤销自己，也不能撤销由配置派生的内置管理员 */
function canRevoke(account: AdminAccount): boolean {
  return !account.bootstrap && account.userId !== identity.value?.userId
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    items.value = await listAdminAccounts()
  } catch (cause) {
    error.value = resolveErrorMessage(cause, t('admin.admins.loadFailed'))
  } finally {
    loading.value = false
  }
}

function openGrant(): void {
  editing.value = null
  editingUser.value = null
  form.value = { userId: '', role: 'MODERATOR', expiresAt: null }
  dialogVisible.value = true
  void searchUsers('')
}

function openEdit(account: AdminAccount): void {
  editing.value = account
  editingUser.value = {
    id: account.userId,
    username: account.username,
    nickname: account.nickname ?? null,
    status: 'ACTIVE',
    platformRole: account.role,
  }
  form.value = { userId: account.userId, role: account.role, expiresAt: account.expiresAt ?? null }
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  if (!editing.value && !form.value.userId.trim()) {
    ElMessage.warning(t('admin.admins.userIdRequired'))
    return
  }
  submitting.value = true
  try {
    if (editing.value) {
      const updated = await changeAdminRole(editing.value.userId, {
        role: form.value.role,
        expiresAt: form.value.expiresAt,
      })
      items.value = items.value.map((item) => (item.userId === updated.userId ? updated : item))
      ElMessage.success(t('admin.admins.updated'))
    } else {
      const created = await grantAdminAccount({
        userId: form.value.userId.trim(),
        role: form.value.role,
        expiresAt: form.value.expiresAt,
      })
      items.value = [...items.value, created]
      ElMessage.success(t('admin.admins.granted'))
    }
    dialogVisible.value = false
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.admins.failed')))
  } finally {
    submitting.value = false
  }
}

async function revoke(account: AdminAccount): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('admin.admins.revokeConfirm', { name: account.nickname || account.username }),
      t('admin.admins.revoke'),
      { type: 'warning', confirmButtonText: t('admin.admins.revoke'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  try {
    await revokeAdminAccount(account.userId)
    items.value = items.value.filter((item) => item.userId !== account.userId)
    ElMessage.success(t('admin.admins.revoked'))
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.admins.failed')))
  }
}

onMounted(load)
</script>

<template>
  <section class="admin-admins">
    <header class="admin-section-head">
      <div>
        <h2 class="admin-section-title">{{ t('admin.layout.admins') }}</h2>
        <p class="admin-section-subtitle">{{ t('admin.admins.subtitle') }}</p>
      </div>
      <div class="admin-admins__actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('common.refresh') }}</el-button>
        <el-button type="primary" :icon="Plus" @click="openGrant">{{ t('admin.admins.grant') }}</el-button>
      </div>
    </header>

    <el-alert
      class="admin-admins__hint"
      type="info"
      show-icon
      :closable="false"
      :title="t('admin.admins.roleHint')"
    />

    <LoadingState v-if="loading && items.length === 0" :rows="3" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <EmptyState v-else-if="items.length === 0" :title="t('admin.admins.empty')" :description="t('admin.admins.emptyHint')" />
    <el-table v-else :data="items" class="admin-table" row-key="id">
      <el-table-column :label="t('common.username')" min-width="180">
        <template #default="{ row }">
          <p>{{ row.nickname || row.username }}</p>
          <p class="cell-sub">@{{ row.username }}</p>
        </template>
      </el-table-column>
      <el-table-column :label="t('admin.admins.role')" width="130">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ roleLabel(row.role) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('admin.admins.expiresAt')" width="170">
        <template #default="{ row }">
          {{ row.expiresAt ? formatDateTime(row.expiresAt) : t('admin.admins.never') }}
        </template>
      </el-table-column>
      <el-table-column :label="t('admin.admins.grantedAt')" width="170">
        <template #default="{ row }">{{ formatDateTime(row.grantedAt) }}</template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="180" align="right">
        <template #default="{ row }">
          <el-tag v-if="row.bootstrap" size="small" type="warning" effect="plain">
            {{ t('admin.admins.bootstrap') }}
          </el-tag>
          <template v-else>
            <el-button size="small" link type="primary" @click="openEdit(row as AdminAccount)">
              {{ t('admin.admins.changeRole') }}
            </el-button>
            <el-button size="small" link type="danger" :disabled="!canRevoke(row as AdminAccount)" @click="revoke(row as AdminAccount)">
              {{ t('admin.admins.revoke') }}
            </el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="460px" append-to-body>
      <el-form label-position="top">
        <el-form-item :label="t('admin.admins.userId')" required>
          <!-- 远程搜索：避免让人去数据库里翻 ULID；已是管理员的账号不出现在候选里 -->
          <el-select
            v-model="form.userId"
            filterable
            remote
            :remote-method="searchUsers"
            :loading="userSearching"
            :disabled="Boolean(editing)"
            :reserve-keyword="false"
            :placeholder="t('admin.admins.userSearchPlaceholder')"
            class="dialog-select"
            :no-data-text="t('admin.admins.userSearchEmpty')"
          >
            <el-option
              v-for="user in editingUser ? [editingUser] : userOptions.filter((item) => !existingAdminIds.has(item.id))"
              :key="user.id"
              :label="userOptionLabel(user)"
              :value="user.id"
            />
          </el-select>
          <p v-if="!editing" class="dialog-hint">{{ t('admin.admins.userSearchHint') }}</p>
        </el-form-item>
        <el-form-item :label="t('admin.admins.role')">
          <el-select v-model="form.role" class="dialog-select">
            <el-option v-for="role in ADMIN_ROLES" :key="role" :label="roleLabel(role)" :value="role" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('admin.admins.expiresAt')">
          <el-date-picker
            v-model="form.expiresAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss[Z]"
            :placeholder="t('admin.admins.never')"
            class="dialog-select"
          />
          <p class="dialog-hint">{{ t('admin.admins.expiresHint') }}</p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">{{ t('common.save') }}</el-button>
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

.admin-admins__actions {
  display: flex;
  gap: var(--isles-space-2);
}

.admin-admins__hint {
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

.dialog-select {
  width: 100%;
}

.dialog-hint {
  margin-top: var(--isles-space-1);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}
</style>
