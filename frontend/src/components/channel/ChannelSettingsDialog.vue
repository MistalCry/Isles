<script setup lang="ts">
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
const isOwner = computed(() => Boolean(props.channel.ownerId && props.channel.ownerId === auth.currentUser?.id) || props.channel.myRoleName === '频道主')

const muteAllActive = computed(
  () => Boolean(props.channel.muteAllUntil && new Date(props.channel.muteAllUntil).getTime() > Date.now()),
)

const muteAllPresets = [
  { label: '10 分钟', value: 600 },
  { label: '1 小时', value: 3600 },
  { label: '6 小时', value: 21600 },
  { label: '1 天', value: 86400 },
  { label: '7 天', value: 604800 },
]

const visibleTabs = computed(() => {
  const tabs = [{ key: 'profile', label: '频道资料' }, { key: 'members', label: '成员' }]
  if (canInvite.value) tabs.push({ key: 'invites', label: '邀请与审批' })
  if (canMuteAll.value) tabs.push({ key: 'mute-all', label: '全员禁言' })
  tabs.push({ key: 'danger', label: '退出 / 解散' })
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
    ElMessage.warning('请输入有效的禁言时长')
    return
  }
  muteAllLoading.value = true
  try {
    await manageApi.setMuteAll(props.channel.id, seconds)
    props.channel.muteAllUntil = new Date(Date.now() + seconds * 1000).toISOString()
    ElMessage.success('已开启全员禁言')
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
    ElMessage.success('已解除全员禁言')
  } catch {
    // 拦截器已提示
  } finally {
    muteAllLoading.value = false
  }
}

async function leaveChannel(): Promise<void> {
  try {
    await ElMessageBox.confirm('退出后你将不再是该频道成员，确认退出？', '退出频道', {
      type: 'warning',
      confirmButtonText: '退出',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  leaving.value = true
  try {
    await channelsApi.leaveChannel(props.channel.id)
    channelStore.removeMyChannel(props.channel.id)
    ElMessage.success('已退出频道')
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
    ElMessage.warning('请输入完整频道名称以确认解散')
    return
  }
  try {
    await ElMessageBox.confirm('解散后频道与内容将无法访问，该操作不可恢复。确认解散？', '解散频道', {
      type: 'error',
      confirmButtonText: '确认解散',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  dissolving.value = true
  try {
    await manageApi.dissolveChannel(props.channel.id)
    channelStore.removeMyChannel(props.channel.id)
    ElMessage.success('频道已解散')
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
    :title="`频道设置 · ${channel.name}`"
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
        <el-button v-if="canManageCenter" :icon="Setting" @click="goManageCenter">进入频道管理中心</el-button>
      </div>
    </div>

    <div v-else-if="activeTab === 'members'" class="settings-panel">
      <p class="settings-panel__hint">
        共 {{ channel.memberCount ?? 0 }} 名成员 · 频道主：{{ isOwner ? '你' : (channel.myRoleName === '频道主' ? '频道主' : '—') }}
      </p>
      <p v-if="rolesLoading" class="muted">正在加载身份组…</p>
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
        :title="`全员禁言中，结束时间：${formatDateTime(channel.muteAllUntil)}`"
        type="warning"
        :closable="false"
        show-icon
        class="settings-panel__alert"
      />
      <p class="settings-panel__hint">开启后仅频道主与超级管理员可发言，普通成员与子板块管理员将被限制发帖/评论。</p>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="禁言时长">
          <el-select v-model="muteAllDuration" class="settings-panel__select">
            <el-option v-for="preset in muteAllPresets" :key="preset.value" :label="preset.label" :value="preset.value" />
            <el-option label="自定义（分钟）" :value="-1" />
          </el-select>
          <el-input-number v-if="muteAllDuration === -1" v-model="customMuteMinutes" :min="1" :max="43200" class="settings-panel__minutes" />
        </el-form-item>
      </el-form>
      <div class="settings-panel__actions">
        <el-button type="warning" :loading="muteAllLoading" @click="applyMuteAll">开启全员禁言</el-button>
        <el-button v-if="muteAllActive" :disabled="muteAllLoading" @click="liftMuteAll">解除禁言</el-button>
      </div>
    </div>

    <div v-else-if="activeTab === 'danger'" class="settings-panel">
      <div v-if="!isOwner" class="danger-block">
        <h3>退出频道</h3>
        <p class="muted">退出后将不再收到该频道的动态，可随时重新加入公开频道。</p>
        <el-button :loading="leaving" @click="leaveChannel">退出频道</el-button>
      </div>

      <div v-if="isOwner" class="danger-block danger-block--danger">
        <h3>解散频道</h3>
        <p class="muted">仅频道主可解散频道。解散后频道与全部内容将不可访问，操作不可恢复。</p>
        <el-input v-model="dissolveConfirm" placeholder="输入完整频道名称以确认" class="settings-panel__select" />
        <el-button type="danger" :loading="dissolving" :disabled="dissolveConfirm.trim() !== channel.name" @click="dissolveChannel">
          解散频道
        </el-button>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
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
