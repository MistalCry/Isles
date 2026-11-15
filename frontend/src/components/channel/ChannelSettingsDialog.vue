<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Setting } from '@element-plus/icons-vue'
import { channelsApi, manageApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { useChannelStore } from '@/stores/channel'
import {
  CHANNEL_PERMISSIONS,
  canManageChannel,
  hasChannelPermission,
  resolveChannelPermissions,
} from '@/utils/channelPermissions'
import { formatDateTime } from '@/utils/format'
import type { Channel, ChannelRoleItem } from '@/types/models'
import ChannelMemberList from './ChannelMemberList.vue'
import ChannelInviteSection from './ChannelInviteSection.vue'
import ChannelProfileForm from './ChannelProfileForm.vue'

const { t } = useI18n()

const props = defineProps<{
  modelValue: boolean
  channel: Channel
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  updated: [channel: Channel]
  left: []
  dissolved: []
}>()

const router = useRouter()
const auth = useAuthStore()
const channelStore = useChannelStore()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const activeTab = ref('profile')
const roles = ref<ChannelRoleItem[]>([])
const rolesLoading = ref(false)
const muteAllLoading = ref(false)
const leaving = ref(false)
const dissolving = ref(false)
const dissolveConfirm = ref('')
const customMuteMinutes = ref(60)
const muteAllDuration = ref(3600)

const permissions = computed(() => resolveChannelPermissions(props.channel, auth.currentUser?.id))
const canManageMembers = computed(
  () =>
    hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.MEMBER_REMOVE) ||
    hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.MEMBER_MUTE) ||
    hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.ROLE_MANAGE),
)
const canAssignRole = computed(() => hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.ROLE_MANAGE))
const canMute = computed(() => hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.MEMBER_MUTE))
const canRemove = computed(() => hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.MEMBER_REMOVE))
const canInvite = computed(
  () =>
    hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.INVITE_CREATE) ||
    hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.INVITE_APPROVE),
)
const canMuteAll = computed(() => hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.MUTE_ALL))
const canManageCenter = computed(() => canManageChannel(permissions.value))
const isOwner = computed(() => Boolean(props.channel.ownerId && props.channel.ownerId === auth.currentUser?.id) || props.channel.myRoleName === t('channel.role.owner'))

const muteAllActive = computed(
  () => Boolean(props.channel.muteAllUntil && new Date(props.channel.muteAllUntil).getTime() > Date.now()),
)

const muteAllPresets = [
  { label: t('duration.min10'), value: 600 },
  { label: t('duration.hour1'), value: 3600 },
  { label: t('duration.hour6'), value: 21600 },
  { label: t('duration.day1'), value: 86400 },
  { label: t('duration.day7'), value: 604800 },
]

const visibleTabs = computed(() => {
  const tabs = [{ key: 'profile', label: t('channel.settings.tabProfile') }, { key: 'members', label: t('common.member') }]
  if (canInvite.value) tabs.push({ key: 'invites', label: t('channel.settings.tabInvite') })
  if (canMuteAll.value) tabs.push({ key: 'mute-all', label: t('channel.settings.tabMuteAll') })
  tabs.push({ key: 'danger', label: t('channel.settings.tabDanger') })
  return tabs
})

function resetDialogState(): void {
  activeTab.value = 'profile'
  dissolveConfirm.value = ''
}

async function loadRoles(): Promise<void> {
  if (!canAssignRole.value) return
  rolesLoading.value = true
  try {
    roles.value = await manageApi.getRoles(props.channel.id)
  } catch {
    roles.value = []
  } finally {
    rolesLoading.value = false
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      resetDialogState()
      void loadRoles()
    }
  },
  { immediate: true },
)

watch(
  () => props.channel.id,
  () => {
    dissolveConfirm.value = ''
    void loadRoles()
  },
)

async function applyMuteAll(): Promise<void> {
  if (!canMuteAll.value) return
  const seconds = muteAllDuration.value === -1 ? customMuteMinutes.value * 60 : muteAllDuration.value
  if (seconds <= 0) {
    ElMessage.warning(t('channel.settings.muteInvalid'))
    return
  }
  muteAllLoading.value = true
  try {
    await manageApi.setMuteAll(props.channel.id, seconds)
    props.channel.muteAllUntil = new Date(Date.now() + seconds * 1000).toISOString()
    ElMessage.success(t('channel.settings.muteAllOn'))
  } catch {
    // 拦截器已提示
  } finally {
    muteAllLoading.value = false
  }
}

async function liftMuteAll(): Promise<void> {
  muteAllLoading.value = true
  try {
    await manageApi.setMuteAll(props.channel.id, 0)
    props.channel.muteAllUntil = null
    ElMessage.success(t('channel.settings.muteAllOff'))
  } catch {
    // 拦截器已提示
  } finally {
    muteAllLoading.value = false
  }
}

async function leaveChannel(): Promise<void> {
  try {
    await ElMessageBox.confirm(t('channel.settings.leaveConfirm'), t('channel.settings.leave'), {
      type: 'warning',
      confirmButtonText: t('channel.settings.leaveAction'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  leaving.value = true
  try {
    await channelsApi.leaveChannel(props.channel.id)
    channelStore.removeMyChannel(props.channel.id)
    ElMessage.success(t('channel.settings.left'))
    visible.value = false
    emit('left')
  } catch {
    // 拦截器已提示
  } finally {
    leaving.value = false
  }
}

async function dissolveChannel(): Promise<void> {
  if (!isOwner.value) return
  if (dissolveConfirm.value.trim() !== props.channel.name) {
    ElMessage.warning(t('channel.settings.dissolveNameRequired'))
    return
  }
  try {
    await ElMessageBox.confirm(t('channel.settings.dissolveConfirm'), t('channel.settings.dissolve'), {
      type: 'error',
      confirmButtonText: t('channel.settings.dissolveAction'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  dissolving.value = true
  try {
    await manageApi.dissolveChannel(props.channel.id)
    channelStore.removeMyChannel(props.channel.id)
    ElMessage.success(t('channel.settings.dissolved'))
    visible.value = false
    emit('dissolved')
  } catch {
    // 拦截器已提示
  } finally {
    dissolving.value = false
  }
}

function goManageCenter(): void {
  visible.value = false
  void router.push({ name: 'manage', params: { channelId: props.channel.id } })
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="t('channel.settings.titleOf', { name: channel.name })"
    width="min(780px, 96vw)"
    top="5vh"
    append-to-body
    class="channel-settings-dialog"
  >
    <el-tabs v-model="activeTab">
      <el-tab-pane v-for="tab in visibleTabs" :key="tab.key" :label="tab.label" :name="tab.key" />
    </el-tabs>

    <div v-if="activeTab === 'profile'" class="settings-panel">
      <ChannelProfileForm :channel="channel" @updated="(updated) => emit('updated', updated)" />
      <div class="settings-panel__actions">
        <el-button v-if="canManageCenter" :icon="Setting" @click="goManageCenter">{{ t('channel.settings.manageCenter') }}</el-button>
      </div>
    </div>

    <div v-else-if="activeTab === 'members'" class="settings-panel">
      <p class="settings-panel__hint">
        {{
          t('channel.settings.memberSummary', {
            n: channel.memberCount ?? 0,
            owner: isOwner
              ? t('channel.settings.you')
              : channel.myRoleName === t('channel.role.owner')
                ? t('channel.role.owner')
                : '—',
          })
        }}
      </p>
      <p v-if="rolesLoading" class="muted">{{ t('channel.settings.roleLoading') }}</p>
      <ChannelMemberList
        :key="`members-${channel.id}`"
        :channel-id="channel.id"
        :roles="roles"
        :can-assign-role="canAssignRole"
        :can-mute="canMute"
        :can-remove="canRemove"
        :compact="!canManageMembers"
      />
    </div>

    <div v-else-if="activeTab === 'invites'" class="settings-panel">
      <ChannelInviteSection
        :key="`invites-${channel.id}`"
        :channel-id="channel.id"
        :can-manage="hasChannelPermission(permissions, CHANNEL_PERMISSIONS.INVITE_CREATE)"
        :can-approve="hasChannelPermission(permissions, CHANNEL_PERMISSIONS.INVITE_APPROVE)"
      />
    </div>

    <div v-else-if="activeTab === 'mute-all'" class="settings-panel">
      <el-alert
        v-if="muteAllActive"
        :title="t('channel.settings.muteAllUntil', { time: formatDateTime(channel.muteAllUntil) })"
        type="warning"
        :closable="false"
        show-icon
        class="settings-panel__alert"
      />
      <p class="settings-panel__hint">{{ t('channel.settings.muteAllHint') }}</p>
      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="t('channel.muteDuration')">
          <el-select v-model="muteAllDuration" class="settings-panel__select">
            <el-option v-for="preset in muteAllPresets" :key="preset.value" :label="preset.label" :value="preset.value" />
            <el-option :label="t('channel.customMinutes')" :value="-1" />
          </el-select>
          <el-input-number v-if="muteAllDuration === -1" v-model="customMuteMinutes" :min="1" :max="43200" class="settings-panel__minutes" />
        </el-form-item>
      </el-form>
      <div class="settings-panel__actions">
        <el-button type="warning" :loading="muteAllLoading" @click="applyMuteAll">{{ t('channel.settings.muteAll') }}</el-button>
        <el-button v-if="muteAllActive" :disabled="muteAllLoading" @click="liftMuteAll">{{ t('channel.member.unmute') }}</el-button>
      </div>
    </div>

    <div v-else-if="activeTab === 'danger'" class="settings-panel">
      <div v-if="!isOwner" class="danger-block">
        <h3>{{ t('channel.settings.leave') }}</h3>
        <p class="muted">{{ t('channel.settings.leaveHint') }}</p>
        <el-button :loading="leaving" @click="leaveChannel">{{ t('channel.settings.leave') }}</el-button>
      </div>

      <div v-if="isOwner" class="danger-block danger-block--danger">
        <h3>{{ t('channel.settings.dissolve') }}</h3>
        <p class="muted">{{ t('channel.settings.dissolveHint') }}</p>
        <el-input v-model="dissolveConfirm" :placeholder="t('channel.dissolveInputPlaceholder')" class="settings-panel__select" />
        <el-button type="danger" :loading="dissolving" :disabled="dissolveConfirm.trim() !== channel.name" @click="dissolveChannel">{{ t('channel.settings.dissolve') }}</el-button>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">{{ t('channel.settings.close') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.settings-panel {
  min-height: 220px;
  padding-top: var(--isles-space-2);
}

.settings-panel__alert {
  margin-bottom: var(--isles-space-3);
}

.settings-panel__hint {
  margin-bottom: var(--isles-space-3);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
}

.settings-panel__select {
  width: 240px;
}

.settings-panel__minutes {
  margin-left: var(--isles-space-3);
}

.settings-panel__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  margin-top: var(--isles-space-4);
}

.settings-profile__media {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-4);
  margin-bottom: var(--isles-space-4);
}

.settings-profile__avatar,
.settings-profile__cover {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
  align-items: flex-start;
}

.settings-profile__cover-preview {
  width: 320px;
  max-width: 60vw;
  height: 96px;
  background-position: center;
  background-size: cover;
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-md);
}

.settings-profile__cover-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 320px;
  max-width: 60vw;
  height: 96px;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
  background: var(--isles-surface-muted);
  border: 1px dashed var(--isles-border-strong);
  border-radius: var(--isles-radius-md);
}

.danger-block {
  padding: var(--isles-space-4);
  margin-bottom: var(--isles-space-4);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-md);

  h3 {
    margin-bottom: var(--isles-space-1);
    font-size: var(--isles-font-md);
    font-weight: 600;
  }

  p {
    margin-bottom: var(--isles-space-3);
    font-size: var(--isles-font-sm);
  }
}

.danger-block--danger {
  border-color: #f0c9c9;
  background: #fff8f8;
}
</style>
