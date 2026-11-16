<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Bottom, Plus, Refresh, Top } from '@element-plus/icons-vue'
import {
  createChannelApp,
  deleteChannelApp,
  listChannelApps,
  reorderChannelApps,
  updateChannelApp,
  type ChannelApp,
  type ChannelAppType,
} from '@/api/apps'
import { resolveErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import {
  CHANNEL_PERMISSIONS,
  hasChannelPermission,
  resolveChannelPermissions,
} from '@/utils/channelPermissions'
import type { Channel } from '@/types/models'
import ImageCropUpload from '@/components/media/ImageCropUpload.vue'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const { t } = useI18n()

const props = defineProps<{
  channel: Channel
  channelId: string
}>()

const auth = useAuthStore()
const apps = ref<ChannelApp[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const dialogOpen = ref(false)
const saving = ref(false)
const editingApp = ref<ChannelApp | null>(null)
const formError = ref<string | null>(null)
const reordering = ref(false)

/**
 * 用 ref 而不是 reactive：Element Plus 的 el-switch / 自定义上传组件的
 * v-model 在 reactive 表单上会被 vue-tsc 判成「需要 .value」，用 ref 自动解包最省事。
 */
const appForm = ref({
  type: 'VOICE_ROOM' as ChannelAppType,
  name: '',
  iconUrl: '',
  /** 后端 SaveAppRequest 只有 description，没有外链字段 */
  description: '',
  enabled: true,
})

const permissions = computed(() => resolveChannelPermissions(props.channel, auth.currentUser?.id))
const canManage = computed(() => hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.CHANNEL_SETTINGS))

const APP_TYPES: ChannelAppType[] = ['VOICE_ROOM', 'LIVE_ROOM', 'CUSTOM']

function typeLabel(type: ChannelAppType): string {
  if (type === 'VOICE_ROOM') return t('manage.apps.typeVoiceRoom')
  if (type === 'LIVE_ROOM') return t('manage.apps.typeLiveRoom')
  return t('manage.apps.typeCustom')
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    apps.value = await listChannelApps(props.channelId)
  } catch (err) {
    error.value = resolveErrorMessage(err, t('manage.apps.loadFailed'))
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editingApp.value = null
  appForm.value.type = 'VOICE_ROOM'
  appForm.value.name = ''
  appForm.value.iconUrl = ''
  appForm.value.description = ''
  appForm.value.enabled = true
  formError.value = null
  dialogOpen.value = true
}

function openEdit(app: ChannelApp): void {
  editingApp.value = app
  appForm.value.type = app.type
  appForm.value.name = app.name
  appForm.value.iconUrl = app.iconUrl ?? ''
  appForm.value.description = app.description ?? ''
  appForm.value.enabled = app.enabled
  formError.value = null
  dialogOpen.value = true
}

async function save(): Promise<void> {
  if (!appForm.value.name.trim()) {
    formError.value = t('manage.apps.nameRequired')
    return
  }
  saving.value = true
  formError.value = null
  const payload = {
    type: appForm.value.type,
    name: appForm.value.name.trim(),
    iconUrl: appForm.value.iconUrl || null,
    description: appForm.value.description.trim() || null,
    enabled: appForm.value.enabled,
  }
  try {
    if (editingApp.value) {
      await updateChannelApp(props.channelId, editingApp.value.id, payload)
      ElMessage.success(t('manage.apps.updated'))
    } else {
      await createChannelApp(props.channelId, { ...payload, sort: apps.value.length })
      ElMessage.success(t('manage.apps.created'))
    }
    dialogOpen.value = false
    await load()
  } catch (err) {
    formError.value = resolveErrorMessage(err, t('manage.apps.saveFailed'))
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(app: ChannelApp): Promise<void> {
  try {
    const updated = await updateChannelApp(props.channelId, app.id, { enabled: !app.enabled })
    app.enabled = updated.enabled
    ElMessage.success(updated.enabled ? t('manage.apps.enabled') : t('manage.apps.disabled'))
  } catch (err) {
    ElMessage.error(resolveErrorMessage(err, t('manage.apps.saveFailed')))
  }
}

async function move(app: ChannelApp, delta: number): Promise<void> {
  const index = apps.value.findIndex((item) => item.id === app.id)
  const target = index + delta
  if (index < 0 || target < 0 || target >= apps.value.length) return
  const next = [...apps.value]
  const [moved] = next.splice(index, 1)
  next.splice(target, 0, moved)
  apps.value = next
  reordering.value = true
  try {
    await reorderChannelApps(
      props.channelId,
      next.map((item) => item.id),
    )
    next.forEach((item, i) => {
      item.sort = i
    })
  } catch (err) {
    ElMessage.error(resolveErrorMessage(err, t('manage.apps.reorderFailed')))
    await load()
  } finally {
    reordering.value = false
  }
}

async function remove(app: ChannelApp): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    t('manage.apps.deleteConfirm', { name: app.name }),
    t('manage.apps.deleteTitle'),
    { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') },
  ).catch(() => false)
  if (!confirmed) return
  try {
    await deleteChannelApp(props.channelId, app.id)
    apps.value = apps.value.filter((item) => item.id !== app.id)
    ElMessage.success(t('manage.apps.deleted'))
  } catch (err) {
    ElMessage.error(resolveErrorMessage(err, t('manage.apps.deleteFailed')))
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
        <h2>{{ t('manage.nav.apps') }}</h2>
        <p class="muted">{{ t('manage.apps.subtitle') }}</p>
      </div>
      <div class="manage-section__actions">
        <el-button :icon="Refresh" @click="load">{{ t('common.refresh') }}</el-button>
        <el-button v-if="canManage" type="primary" :icon="Plus" @click="openCreate">
          {{ t('manage.apps.create') }}
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="!canManage"
      :title="t('manage.apps.readonly')"
      type="info"
      :closable="false"
      show-icon
      class="manage-section__alert"
    />

    <LoadingState v-if="loading && apps.length === 0" :rows="3" :tip="t('common.loading')" />
    <ErrorState v-else-if="error && apps.length === 0" :message="error" @retry="load" />
    <EmptyState
      v-else-if="apps.length === 0"
      :title="t('manage.apps.empty')"
      :description="t('manage.apps.emptyHint')"
    />

    <ul v-else class="app-list">
      <li v-for="(app, index) in apps" :key="app.id" class="app-card isles-card">
        <span class="app-card__icon">
          <img v-if="app.iconUrl" :src="app.iconUrl" :alt="app.name" />
          <el-icon v-else :size="18"><Top /></el-icon>
        </span>
        <div class="app-card__main">
          <strong>{{ app.name }}</strong>
          <span class="manage-section__tag">{{ typeLabel(app.type) }}</span>
          <span v-if="!app.enabled" class="manage-section__tag manage-section__tag--muted">
            {{ t('manage.apps.statusOff') }}
          </span>
          <p v-if="app.description" class="muted app-card__url">{{ app.description }}</p>
        </div>
        <div v-if="canManage" class="app-card__actions">
          <el-button
            text
            size="small"
            :icon="Top"
            :disabled="index === 0 || reordering"
            :aria-label="t('manage.apps.moveUp')"
            @click="move(app, -1)"
          />
          <el-button
            text
            size="small"
            :icon="Bottom"
            :disabled="index === apps.length - 1 || reordering"
            :aria-label="t('manage.apps.moveDown')"
            @click="move(app, 1)"
          />
          <el-switch :model-value="app.enabled" size="small" @change="toggleEnabled(app)" />
          <el-button text size="small" @click="openEdit(app)">{{ t('common.edit') }}</el-button>
          <el-button text size="small" type="danger" @click="remove(app)">{{ t('common.delete') }}</el-button>
        </div>
      </li>
    </ul>

    <el-dialog
      v-model="dialogOpen"
      :title="editingApp ? t('manage.apps.edit') : t('manage.apps.create')"
      width="480px"
      append-to-body
    >
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" show-icon class="manage-section__alert" />
      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="t('manage.apps.type')" required>
          <el-select v-model="appForm.type" class="app-form__select">
            <el-option v-for="type in APP_TYPES" :key="type" :label="typeLabel(type)" :value="type" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('manage.apps.name')" required>
          <el-input v-model="appForm.name" maxlength="24" show-word-limit />
        </el-form-item>
        <el-form-item :label="t('manage.apps.description')">
          <el-input v-model="appForm.description" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item :label="t('manage.apps.icon')">
          <ImageCropUpload
            v-model="appForm.iconUrl"
            :aspect-ratio="1"
            :max-output-width="256"
            shape="square"
            :box-width="240"
            :label="t('manage.apps.icon')"
            :hint="t('manage.apps.iconHint')"
          />
        </el-form-item>
        <el-form-item :label="t('manage.apps.enabled')">
          <el-switch v-model="appForm.enabled" />
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
.app-list {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
  padding: 0;
  margin: 0;
  list-style: none;
}

.app-card {
  display: flex;
  gap: var(--isles-space-3);
  align-items: center;
  padding: var(--isles-space-3);
}

.app-card__icon {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  overflow: hidden;
  color: var(--isles-primary);
  background: var(--isles-primary-soft);
  border-radius: var(--isles-radius-md);

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.app-card__main {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
  min-width: 0;
}

.app-card__url {
  flex-basis: 100%;
  overflow: hidden;
  font-size: var(--isles-font-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-card__actions {
  display: flex;
  flex: none;
  gap: 2px;
  align-items: center;
}

.app-form__select {
  width: 100%;
}

.manage-section__tag--muted {
  color: var(--isles-text-muted);
  background: var(--isles-surface-muted);
}
</style>
