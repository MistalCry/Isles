<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingState from '@/components/LoadingState.vue'
import {
  listAdminComments,
  listAdminPosts,
  restoreAdminComment,
  restoreAdminPost,
  takedownAdminComment,
  takedownAdminPost,
  type AdminContentItem,
  type AdminContentPage,
} from './adminApi'
import { resolveErrorMessage } from '@/api/client'
import { formatDateTime } from '@/utils/format'
import { hasPermission } from './adminPermissions'
import { useAdminAccess } from '@/composables/useAdminAccess'

const { t } = useI18n()
const { identity } = useAdminAccess()
const canTakedown = computed(() => hasPermission(identity.value, 'CONTENT_TAKEDOWN'))

type TabKey = 'posts' | 'comments'

const tab = ref<TabKey>('posts')
const keyword = ref('')
const status = ref('')
const page = ref(1)
const size = ref(20)
const data = ref<AdminContentPage | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

const takedownVisible = ref(false)
const takedownTarget = ref<AdminContentItem | null>(null)
const takedownReason = ref('')
const submitting = ref(false)

const statusOptions = computed(() => [
  { label: t('admin.content.statusPublished'), value: 'PUBLISHED' },
  { label: t('admin.content.statusHidden'), value: 'HIDDEN' },
])

function statusLabel(value?: string | null): string {
  if (value === 'HIDDEN') return t('admin.content.statusHidden')
  if (value === 'PUBLISHED') return t('admin.content.statusPublished')
  return value ?? '—'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  const params = {
    keyword: keyword.value.trim() || undefined,
    status: status.value || undefined,
    page: page.value,
    size: size.value,
  }
  try {
    data.value = tab.value === 'posts' ? await listAdminPosts(params) : await listAdminComments(params)
  } catch (cause) {
    error.value = resolveErrorMessage(cause, t('admin.content.loadFailed'))
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
  void load()
}

function switchTab(next: TabKey): void {
  if (tab.value === next) return
  tab.value = next
  data.value = null
  search()
}

function openTakedown(item: AdminContentItem): void {
  takedownTarget.value = item
  takedownReason.value = ''
  takedownVisible.value = true
}

async function submitTakedown(): Promise<void> {
  const target = takedownTarget.value
  if (!target) return
  if (!takedownReason.value.trim()) {
    ElMessage.warning(t('admin.content.reasonRequired'))
    return
  }
  submitting.value = true
  try {
    if (target.targetType === 'COMMENT' || tab.value === 'comments') {
      await takedownAdminComment(target.id, takedownReason.value.trim())
    } else {
      await takedownAdminPost(target.id, takedownReason.value.trim())
    }
    ElMessage.success(t('admin.content.takedownDone'))
    takedownVisible.value = false
    await load()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.content.failed')))
  } finally {
    submitting.value = false
  }
}

async function restore(item: AdminContentItem): Promise<void> {
  try {
    await ElMessageBox.confirm(t('admin.content.restoreConfirm'), t('admin.content.restore'), {
      type: 'warning',
      confirmButtonText: t('admin.content.restore'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  try {
    if (tab.value === 'comments') {
      await restoreAdminComment(item.id)
    } else {
      await restoreAdminPost(item.id)
    }
    ElMessage.success(t('admin.content.restored'))
    await load()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.content.failed')))
  }
}

watch(tab, () => {
  page.value = 1
})

onMounted(load)
</script>

<template>
  <section class="admin-content-view">
    <header class="admin-section-head">
      <div>
        <h2 class="admin-section-title">{{ t('admin.layout.content') }}</h2>
        <p class="admin-section-subtitle">{{ t('admin.content.subtitle') }}</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('common.refresh') }}</el-button>
    </header>

    <div class="content-tabs">
      <button type="button" class="content-tab" :class="{ 'is-active': tab === 'posts' }" @click="switchTab('posts')">
        {{ t('admin.content.tabPosts') }}
      </button>
      <button type="button" class="content-tab" :class="{ 'is-active': tab === 'comments' }" @click="switchTab('comments')">
        {{ t('admin.content.tabComments') }}
      </button>
    </div>

    <div class="admin-filters">
      <el-input
        v-model="keyword"
        :placeholder="t('admin.content.keywordPlaceholder')"
        :prefix-icon="Search"
        clearable
        class="admin-filters__search"
        @keyup.enter="search"
        @clear="search"
      />
      <el-select v-model="status" :placeholder="t('common.allStatus')" clearable class="admin-filters__select" @change="search">
        <el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-button type="primary" :icon="Search" @click="search">{{ t('common.query') }}</el-button>
    </div>

    <LoadingState v-if="loading && !data" :rows="4" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <EmptyState
      v-else-if="data && data.items.length === 0"
      :title="t('admin.content.empty')"
      :description="t('admin.content.emptyHint')"
    />
    <template v-else-if="data">
      <div class="admin-table-scroll">
      <el-table :data="data.items" class="admin-table" row-key="id">
        <el-table-column :label="t('admin.content.title')" min-width="260">
          <template #default="{ row }">
            <p class="content-title">{{ row.title || t('admin.content.untitled') }}</p>
            <p class="cell-sub line-clamp-2">{{ row.contentPreview }}</p>
          </template>
        </el-table-column>
        <el-table-column :label="t('admin.content.author')" width="150">
          <template #default="{ row }">{{ row.authorId || '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('common.status')" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="row.status === 'HIDDEN' ? 'info' : 'success'">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.time')" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="150" align="right" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status !== 'HIDDEN'"
              size="small"
              link
              type="danger"
              :disabled="!canTakedown"
              @click="openTakedown(row as AdminContentItem)"
            >
              {{ t('admin.content.takedown') }}
            </el-button>
            <el-button
              v-else
              size="small"
              link
              type="primary"
              :disabled="!canTakedown"
              @click="restore(row as AdminContentItem)"
            >
              {{ t('admin.content.restore') }}
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

    <el-dialog v-model="takedownVisible" :title="t('admin.content.takedownTitle')" width="460px" append-to-body>
      <p class="dialog-hint">
        {{ takedownTarget?.title || takedownTarget?.contentPreview || takedownTarget?.id }}
      </p>
      <el-form label-position="top">
        <el-form-item :label="t('admin.content.takedownReason')" required>
          <el-input
            v-model="takedownReason"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            :placeholder="t('admin.content.takedownReasonPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="takedownVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="danger" :loading="submitting" @click="submitTakedown">{{ t('admin.content.takedown') }}</el-button>
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

.content-tabs {
  display: flex;
  gap: var(--isles-space-2);
  margin-bottom: var(--isles-space-3);
}

.content-tab {
  padding: var(--isles-space-1) var(--isles-space-3);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
  cursor: pointer;
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: 999px;

  &.is-active {
    color: var(--isles-primary);
    background: var(--isles-primary-soft);
    border-color: var(--isles-primary);
  }
}

.admin-filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  margin-bottom: var(--isles-space-3);
}

.admin-filters__search {
  width: 260px;
}

.admin-filters__select {
  width: 140px;
}

.admin-table {
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
}

.content-title {
  font-weight: 600;
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
</style>
