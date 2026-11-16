<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import {
  cancelChannelActivity,
  createChannelActivity,
  deleteChannelActivity,
  listManageActivities,
  publishChannelActivity,
  updateChannelActivity,
  type ActivityStatus,
  type ChannelActivity,
} from '@/api/activities'
import { resolveErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import {
  CHANNEL_PERMISSIONS,
  hasChannelPermission,
  resolveChannelPermissions,
} from '@/utils/channelPermissions'
import { formatDateTime } from '@/utils/format'
import type { Channel } from '@/types/models'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const { t } = useI18n()

const props = defineProps<{
  channel: Channel
  channelId: string
}>()

const auth = useAuthStore()
const activities = ref<ChannelActivity[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const dialogOpen = ref(false)
const saving = ref(false)
const editing = ref<ChannelActivity | null>(null)
const formError = ref<string | null>(null)
const busyId = ref<string | null>(null)

const form = ref({
  title: '',
  location: '',
  description: '',
  startsAt: '',
  endsAt: '',
  capacity: 0,
})

const permissions = computed(() => resolveChannelPermissions(props.channel, auth.currentUser?.id))
const canManage = computed(() => hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.CHANNEL_SETTINGS))

function statusLabel(activity: ChannelActivity): string {
  if (activity.publishState === 'DRAFT') return t('activities.draft')
  if (activity.publishState === 'CANCELLED') return t('activities.cancelledState')
  return timeStatusLabel(activity.status)
}

function timeStatusLabel(status: ActivityStatus): string {
  if (status === 'UPCOMING') return t('activities.upcoming')
  if (status === 'ONGOING') return t('activities.ongoing')
  return t('activities.ended')
}

function statusTagType(activity: ChannelActivity): 'primary' | 'success' | 'info' | 'warning' {
  if (activity.publishState === 'DRAFT') return 'warning'
  if (activity.publishState === 'CANCELLED') return 'info'
  if (activity.status === 'ONGOING') return 'success'
  if (activity.status === 'ENDED') return 'info'
  return 'primary'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    activities.value = await listManageActivities(props.channelId)
  } catch (err) {
    error.value = resolveErrorMessage(err, t('activities.loadFailed'))
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editing.value = null
  form.value = { title: '', location: '', description: '', startsAt: '', endsAt: '', capacity: 0 }
  formError.value = null
  dialogOpen.value = true
}

function openEdit(activity: ChannelActivity): void {
  editing.value = activity
  form.value = {
    title: activity.title,
    location: activity.location ?? '',
    description: activity.description ?? '',
    startsAt: activity.startsAt,
    endsAt: activity.endsAt ?? '',
    capacity: activity.capacity ?? 0,
  }
  formError.value = null
  dialogOpen.value = true
}

async function save(): Promise<void> {
  if (!form.value.title.trim()) {
    formError.value = t('activities.titleRequired')
    return
  }
  if (!form.value.startsAt) {
    formError.value = t('activities.startRequired')
    return
  }
  saving.value = true
  formError.value = null
  const payload = {
    title: form.value.title.trim(),
    description: form.value.description.trim() || null,
    location: form.value.location.trim() || null,
    startsAt: new Date(form.value.startsAt).toISOString(),
    endsAt: form.value.endsAt ? new Date(form.value.endsAt).toISOString() : null,
    capacity: form.value.capacity > 0 ? form.value.capacity : null,
  }
  try {
    if (editing.value) {
      await updateChannelActivity(editing.value.id, payload)
      ElMessage.success(t('activities.updated'))
    } else {
      // 后端 create 落的是草稿：发布一次，成员才在频道里看得到
      const created = await createChannelActivity(props.channelId, payload)
      if (created) await publishChannelActivity(created.id)
      ElMessage.success(t('activities.created'))
    }
    dialogOpen.value = false
    await load()
  } catch (err) {
    formError.value = resolveErrorMessage(err, t('activities.saveFailed'))
  } finally {
    saving.value = false
  }
}

async function publish(activity: ChannelActivity): Promise<void> {
  busyId.value = activity.id
  try {
    await publishChannelActivity(activity.id)
    ElMessage.success(t('activities.published'))
    await load()
  } catch (err) {
    ElMessage.error(resolveErrorMessage(err, t('activities.publishFailed')))
  } finally {
    busyId.value = null
  }
}

async function cancelActivity(activity: ChannelActivity): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    t('activities.cancelConfirm', { title: activity.title }),
    t('activities.cancelTitle'),
    { type: 'warning', confirmButtonText: t('activities.cancel'), cancelButtonText: t('common.cancel') },
  ).catch(() => false)
  if (!confirmed) return
  busyId.value = activity.id
  try {
    await cancelChannelActivity(activity.id)
    ElMessage.success(t('activities.cancelled'))
    await load()
  } catch (err) {
    ElMessage.error(resolveErrorMessage(err, t('activities.cancelFailed')))
  } finally {
    busyId.value = null
  }
}

async function removeActivity(activity: ChannelActivity): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    t('activities.deleteConfirm', { title: activity.title }),
    t('activities.deleteTitle'),
    { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') },
  ).catch(() => false)
  if (!confirmed) return
  busyId.value = activity.id
  try {
    await deleteChannelActivity(activity.id)
    activities.value = activities.value.filter((item) => item.id !== activity.id)
    ElMessage.success(t('activities.deleted'))
  } catch (err) {
    ElMessage.error(resolveErrorMessage(err, t('activities.deleteFailed')))
  } finally {
    busyId.value = null
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="manage-section">
    <header class="manage-section__header">
      <div>
        <h2>{{ t('manage.nav.activities') }}</h2>
        <p class="muted">{{ t('activities.manageSubtitle') }}</p>
      </div>
      <div class="manage-section__actions">
        <el-button :icon="Refresh" @click="load">{{ t('common.refresh') }}</el-button>
        <el-button v-if="canManage" type="primary" :icon="Plus" @click="openCreate">
          {{ t('activities.create') }}
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="!canManage"
      :title="t('activities.readonly')"
      type="info"
      :closable="false"
      show-icon
      class="manage-section__alert"
    />

    <LoadingState v-if="loading && activities.length === 0" :rows="3" :tip="t('common.loading')" />
    <ErrorState v-else-if="error && activities.length === 0" :message="error" @retry="load" />
    <EmptyState
      v-else-if="activities.length === 0"
      :title="t('activities.empty')"
      :description="t('activities.emptyHint')"
    />

    <ul v-else class="activity-list">
      <li v-for="activity in activities" :key="activity.id" class="activity-card isles-card">
        <div class="activity-card__main">
          <div class="activity-card__title">
            <strong>{{ activity.title }}</strong>
            <el-tag size="small" :type="statusTagType(activity)" effect="plain">{{ statusLabel(activity) }}</el-tag>
          </div>
          <p class="muted activity-card__meta">
            {{ formatDateTime(activity.startsAt) }}
            <span v-if="activity.location"> · {{ activity.location }}</span>
            <span> · {{ t('activities.signupCount', { n: activity.signupCount }) }}</span>
            <span v-if="activity.capacity"> / {{ activity.capacity }}</span>
          </p>
          <p v-if="activity.description" class="activity-card__desc">{{ activity.description }}</p>
        </div>
        <div v-if="canManage" class="activity-card__actions">
          <el-button
            v-if="activity.publishState === 'DRAFT'"
            text
            size="small"
            type="primary"
            :loading="busyId === activity.id"
            @click="publish(activity)"
          >{{ t('activities.publish') }}</el-button>
          <el-button text size="small" @click="openEdit(activity)">{{ t('common.edit') }}</el-button>
          <el-button
            v-if="activity.publishState === 'PUBLISHED'"
            text
            size="small"
            type="danger"
            :loading="busyId === activity.id"
            @click="cancelActivity(activity)"
          >{{ t('activities.cancel') }}</el-button>
          <el-button
            v-else
            text
            size="small"
            type="danger"
            :loading="busyId === activity.id"
            @click="removeActivity(activity)"
          >{{ t('common.delete') }}</el-button>
        </div>
      </li>
    </ul>

    <el-dialog
      v-model="dialogOpen"
      :title="editing ? t('activities.edit') : t('activities.create')"
      width="520px"
      append-to-body
    >
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" show-icon class="manage-section__alert" />
      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="t('activities.title')" required>
          <el-input v-model="form.title" maxlength="60" show-word-limit />
        </el-form-item>
        <el-form-item :label="t('activities.start')" required>
          <el-date-picker
            v-model="form.startsAt"
            type="datetime"
            class="activity-form__picker"
            :placeholder="t('activities.startPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('activities.end')">
          <el-date-picker
            v-model="form.endsAt"
            type="datetime"
            class="activity-form__picker"
            :placeholder="t('activities.endPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('activities.location')">
          <el-input v-model="form.location" maxlength="80" :placeholder="t('activities.locationPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('activities.capacity')">
          <el-input-number v-model="form.capacity" :min="0" :max="9999" />
          <span class="form-hint">{{ t('activities.capacityHint') }}</span>
        </el-form-item>
        <el-form-item :label="t('activities.description')">
          <el-input v-model="form.description" type="textarea" :rows="4" maxlength="1000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped lang="scss">
.activity-list {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
  padding: 0;
  margin: 0;
  list-style: none;
}

.activity-card {
  display: flex;
  gap: var(--isles-space-3);
  align-items: flex-start;
  justify-content: space-between;
  padding: var(--isles-space-3);
}

.activity-card__main {
  flex: 1;
  min-width: 0;
}

.activity-card__title {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
}

.activity-card__meta {
  margin-top: var(--isles-space-1);
  font-size: var(--isles-font-xs);
}

.activity-card__desc {
  margin-top: var(--isles-space-2);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
}

.activity-card__actions {
  display: flex;
  flex: none;
  gap: 2px;
}

.activity-form__picker {
  width: 100%;
}

.form-hint {
  margin-left: var(--isles-space-2);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}
</style>
