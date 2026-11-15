<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingState from '@/components/LoadingState.vue'
import {
  archiveAdminAnnouncement,
  createAdminAnnouncement,
  deleteAdminAnnouncement,
  listAdminAnnouncements,
  publishAdminAnnouncement,
  updateAdminAnnouncement,
  type AdminAnnouncement,
  type AdminAnnouncementPage,
  type AnnouncementLevel,
  type AnnouncementPlacement,
  type AnnouncementStatus,
} from './adminApi'
import { resolveErrorMessage } from '@/api/client'
import { formatDateTime } from '@/utils/format'

const { t } = useI18n()

const LEVELS: AnnouncementLevel[] = ['INFO', 'WARNING', 'CRITICAL']
const PLACEMENTS: AnnouncementPlacement[] = ['BANNER', 'MODAL', 'INBOX']

const status = ref('')
const page = ref(1)
const size = ref(20)
const data = ref<AdminAnnouncementPage | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

const dialogVisible = ref(false)
const editing = ref<AdminAnnouncement | null>(null)
const form = ref<{
  title: string
  content: string
  level: AnnouncementLevel
  placement: AnnouncementPlacement
  startAt: string | null
  endAt: string | null
}>({ title: '', content: '', level: 'INFO', placement: 'BANNER', startAt: null, endAt: null })
const submitting = ref(false)

const dialogTitle = computed(() =>
  editing.value ? t('admin.announcements.editTitle') : t('admin.announcements.createTitle'),
)

const STATUS_LABEL_KEYS: Record<AnnouncementStatus, string> = {
  DRAFT: 'admin.announcements.statusDraft',
  PUBLISHED: 'admin.announcements.statusPublished',
  ARCHIVED: 'admin.announcements.statusArchived',
}

function statusLabel(value?: string | null): string {
  const key = STATUS_LABEL_KEYS[(value ?? 'DRAFT') as AnnouncementStatus]
  return key ? t(key) : (value ?? '—')
}

function statusTagType(value?: string | null): 'info' | 'success' | 'warning' {
  if (value === 'PUBLISHED') return 'success'
  if (value === 'ARCHIVED') return 'info'
  return 'warning'
}

function levelLabel(value?: string | null): string {
  if (value === 'WARNING') return t('announcement.level.warning')
  if (value === 'CRITICAL') return t('announcement.level.critical')
  return t('announcement.level.info')
}

function levelTagType(value?: string | null): 'success' | 'warning' | 'danger' {
  if (value === 'CRITICAL') return 'danger'
  if (value === 'WARNING') return 'warning'
  return 'success'
}

function placementLabel(value?: string | null): string {
  if (value === 'MODAL') return t('admin.announcements.placementModal')
  if (value === 'INBOX') return t('admin.announcements.placementInbox')
  return t('admin.announcements.placementBanner')
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    data.value = await listAdminAnnouncements({
      status: status.value || undefined,
      page: page.value,
      size: size.value,
    })
  } catch (cause) {
    error.value = resolveErrorMessage(cause, t('admin.announcements.loadFailed'))
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
  void load()
}

function openCreate(): void {
  editing.value = null
  form.value = { title: '', content: '', level: 'INFO', placement: 'BANNER', startAt: null, endAt: null }
  dialogVisible.value = true
}

function openEdit(item: AdminAnnouncement): void {
  editing.value = item
  form.value = {
    title: item.title,
    content: item.content,
    level: (item.level as AnnouncementLevel) ?? 'INFO',
    placement: (item.placement as AnnouncementPlacement) ?? 'BANNER',
    startAt: item.startAt ?? null,
    endAt: item.endAt ?? null,
  }
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  if (!form.value.title.trim()) {
    ElMessage.warning(t('admin.announcements.titleRequired'))
    return
  }
  if (!form.value.content.trim()) {
    ElMessage.warning(t('admin.announcements.contentRequired'))
    return
  }
  submitting.value = true
  const payload = {
    title: form.value.title.trim(),
    content: form.value.content.trim(),
    level: form.value.level,
    placement: form.value.placement,
    startAt: form.value.startAt,
    endAt: form.value.endAt,
  }
  try {
    if (editing.value) {
      await updateAdminAnnouncement(editing.value.id, payload)
    } else {
      await createAdminAnnouncement(payload)
    }
    ElMessage.success(t('admin.announcements.saved'))
    dialogVisible.value = false
    await load()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.announcements.failed')))
  } finally {
    submitting.value = false
  }
}

async function publish(item: AdminAnnouncement): Promise<void> {
  try {
    await ElMessageBox.confirm(t('admin.announcements.publishConfirm', { title: item.title }), t('admin.announcements.publish'), {
      type: 'warning',
      confirmButtonText: t('admin.announcements.publish'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  try {
    await publishAdminAnnouncement(item.id)
    ElMessage.success(t('admin.announcements.published'))
    await load()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.announcements.failed')))
  }
}

async function archive(item: AdminAnnouncement): Promise<void> {
  try {
    await archiveAdminAnnouncement(item.id)
    ElMessage.success(t('admin.announcements.archived'))
    await load()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.announcements.failed')))
  }
}

async function remove(item: AdminAnnouncement): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('admin.announcements.deleteConfirm', { title: item.title }),
      t('common.delete'),
      { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') },
    )
  } catch {
    return
  }
  try {
    await deleteAdminAnnouncement(item.id)
    ElMessage.success(t('admin.announcements.deleted'))
    await load()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.announcements.failed')))
  }
}

onMounted(load)
</script>

<template>
  <section class="admin-announcements">
    <header class="admin-section-head">
      <div>
        <h2 class="admin-section-title">{{ t('admin.layout.announcements') }}</h2>
        <p class="admin-section-subtitle">{{ t('admin.announcements.subtitle') }}</p>
      </div>
      <div class="admin-announcements__actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('common.refresh') }}</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">{{ t('admin.announcements.create') }}</el-button>
      </div>
    </header>

    <div class="admin-filters">
      <el-select v-model="status" :placeholder="t('common.allStatus')" clearable class="admin-filters__select" @change="search">
        <el-option :label="t('admin.announcements.statusDraft')" value="DRAFT" />
        <el-option :label="t('admin.announcements.statusPublished')" value="PUBLISHED" />
        <el-option :label="t('admin.announcements.statusArchived')" value="ARCHIVED" />
      </el-select>
    </div>

    <LoadingState v-if="loading && !data" :rows="4" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <EmptyState
      v-else-if="data && data.items.length === 0"
      :title="t('admin.announcements.empty')"
      :description="t('admin.announcements.emptyHint')"
    />
    <template v-else-if="data">
      <el-table :data="data.items" class="admin-table" row-key="id">
        <el-table-column :label="t('admin.announcements.titleField')" min-width="240">
          <template #default="{ row }">
            <p class="announcement-title">{{ row.title }}</p>
            <p class="cell-sub line-clamp-2">{{ row.content }}</p>
          </template>
        </el-table-column>
        <el-table-column :label="t('admin.announcements.level')" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="levelTagType(row.level)">{{ levelLabel(row.level) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('admin.announcements.placement')" width="120">
          <template #default="{ row }">{{ placementLabel(row.placement) }}</template>
        </el-table-column>
        <el-table-column :label="t('common.status')" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="statusTagType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('admin.announcements.timeRange')" width="230">
          <template #default="{ row }">
            <p class="cell-sub">{{ row.startAt ? formatDateTime(row.startAt) : t('admin.announcements.immediate') }}</p>
            <p class="cell-sub">{{ row.endAt ? formatDateTime(row.endAt) : t('admin.announcements.forever') }}</p>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="220" align="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openEdit(row as AdminAnnouncement)">
              {{ t('common.edit') }}
            </el-button>
            <el-button
              v-if="row.status === 'DRAFT'"
              size="small"
              link
              type="success"
              @click="publish(row as AdminAnnouncement)"
            >
              {{ t('admin.announcements.publish') }}
            </el-button>
            <el-button
              v-if="row.status === 'PUBLISHED'"
              size="small"
              link
              @click="archive(row as AdminAnnouncement)"
            >
              {{ t('admin.announcements.archive') }}
            </el-button>
            <el-button size="small" link type="danger" @click="remove(row as AdminAnnouncement)">
              {{ t('common.delete') }}
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

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px" append-to-body>
      <el-form label-position="top">
        <el-form-item :label="t('admin.announcements.titleField')" required>
          <el-input v-model="form.title" maxlength="120" show-word-limit />
        </el-form-item>
        <el-form-item :label="t('admin.announcements.content')" required>
          <el-input v-model="form.content" type="textarea" :rows="4" maxlength="1000" show-word-limit />
        </el-form-item>
        <div class="dialog-grid">
          <el-form-item :label="t('admin.announcements.level')">
            <el-select v-model="form.level" class="dialog-select">
              <el-option v-for="item in LEVELS" :key="item" :label="levelLabel(item)" :value="item" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('admin.announcements.placement')">
            <el-select v-model="form.placement" class="dialog-select">
              <el-option v-for="item in PLACEMENTS" :key="item" :label="placementLabel(item)" :value="item" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item :label="t('admin.announcements.timeRange')">
          <el-date-picker
            v-model="form.startAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss[Z]"
            :placeholder="t('admin.announcements.immediate')"
            class="dialog-select"
          />
        </el-form-item>
        <el-form-item :label="t('admin.announcements.endAt')">
          <el-date-picker
            v-model="form.endAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss[Z]"
            :placeholder="t('admin.announcements.forever')"
            class="dialog-select"
          />
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

.admin-announcements__actions {
  display: flex;
  gap: var(--isles-space-2);
}

.admin-filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  margin-bottom: var(--isles-space-3);
}

.admin-filters__select {
  width: 150px;
}

.admin-table {
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
}

.announcement-title {
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

.dialog-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--isles-space-3);
}

.dialog-select {
  width: 100%;
}
</style>
