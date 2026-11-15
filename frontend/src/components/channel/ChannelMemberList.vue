<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { manageApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime, formatRelativeTime } from '@/utils/format'
import type { ChannelMemberItem, ChannelRoleItem } from '@/types/models'
import UserAvatar from '../UserAvatar.vue'
import UserCardPopover from '../UserCardPopover.vue'
import LoadingState from '../LoadingState.vue'
import ErrorState from '../ErrorState.vue'
import EmptyState from '../EmptyState.vue'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    channelId: string
    roles?: ChannelRoleItem[]
    canAssignRole?: boolean
    canMute?: boolean
    canRemove?: boolean
    compact?: boolean
    pageSize?: number
  }>(),
  {
    roles: () => [],
    canAssignRole: false,
    canMute: false,
    canRemove: false,
    compact: false,
    pageSize: 20,
  },
)

const emit = defineEmits<{
  changed: []
}>()

const auth = useAuthStore()
const members = ref<ChannelMemberItem[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const keyword = ref('')
const page = ref(1)
const total = ref(0)
const mutingIds = ref<Set<string>>(new Set())

const hasMore = computed(() => members.value.length < total.value)
const mutedDurations = [
  { label: t('duration.min10'), seconds: 600 },
  { label: t('duration.hour1'), seconds: 3600 },
  { label: t('duration.day1'), seconds: 86400 },
  { label: t('duration.day7'), seconds: 604800 },
]

async function load(reset = false): Promise<void> {
  if (reset) {
    page.value = 1
    members.value = []
    error.value = null
  }
  loading.value = true
  try {
    const result = await manageApi.getMembers(props.channelId, {
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: props.pageSize,
    })
    members.value = reset ? result.items : [...members.value, ...result.items]
    total.value = result.total ?? members.value.length
  } catch (err) {
    error.value = resolveErrorMessage(err, t('mention.loadFailed'))
  } finally {
    loading.value = false
  }
}

function search(): void {
  void load(true)
}

async function loadMore(): Promise<void> {
  if (loading.value || !hasMore.value) return
  page.value += 1
  await load(false)
}

function memberName(member: ChannelMemberItem): string {
  return member.nickname || member.username || t('common.islander')
}

function isMuted(member: ChannelMemberItem): boolean {
  if (member.status === 'MUTED') return true
  return Boolean(member.mutedUntil && new Date(member.mutedUntil).getTime() > Date.now())
}

function isOwnerRow(member: ChannelMemberItem): boolean {
  return member.roleName === t('channel.role.owner')
}

async function assignRole(member: ChannelMemberItem, roleId: string): Promise<void> {
  if (!props.canAssignRole || roleId === member.roleId) return
  try {
    await manageApi.assignMemberRole(props.channelId, member.userId, roleId)
    const role = props.roles.find((item) => item.id === roleId)
    member.roleId = roleId
    member.roleName = role?.name ?? member.roleName
    ElMessage.success(t('channel.member.roleUpdated'))
    emit('changed')
  } catch {
    // 拦截器已提示
  }
}

async function mute(member: ChannelMemberItem, seconds: number): Promise<void> {
  if (!props.canMute) return
  if (mutingIds.value.has(member.userId)) return
  mutingIds.value.add(member.userId)
  try {
    await manageApi.muteMember(props.channelId, member.userId, seconds)
    member.status = 'MUTED'
    member.mutedUntil = new Date(Date.now() + seconds * 1000).toISOString()
    ElMessage.success(t('channel.member.mutedToast', { name: memberName(member) }))
  } catch {
    // 拦截器已提示
  } finally {
    mutingIds.value.delete(member.userId)
  }
}

async function unmute(member: ChannelMemberItem): Promise<void> {
  try {
    await manageApi.unmuteMember(props.channelId, member.userId)
    member.status = 'ACTIVE'
    member.mutedUntil = null
    ElMessage.success(t('channel.member.unmuted'))
  } catch {
    // 拦截器已提示
  }
}

async function remove(member: ChannelMemberItem): Promise<void> {
  if (!props.canRemove) return
  try {
    await ElMessageBox.confirm(
      t('channel.member.removeConfirm', { name: memberName(member) }),
      t('channel.member.remove'),
      {
      type: 'warning',
      confirmButtonText: t('common.remove'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  try {
    await manageApi.removeMember(props.channelId, member.userId)
    members.value = members.value.filter((item) => item.userId !== member.userId)
    total.value = Math.max(0, total.value - 1)
    ElMessage.success(t('channel.member.removed'))
    emit('changed')
  } catch {
    // 拦截器已提示
  }
}

function handleAssignCommand(member: ChannelMemberItem, command: string | number | object): void {
  void assignRole(member, String(command))
}

function handleMuteCommand(member: ChannelMemberItem, command: string | number | object): void {
  void mute(member, Number(command))
}

onMounted(() => {
  void load(true)
})

defineExpose({ reload: () => load(true) })
</script>

<template>
  <div class="member-list" :class="{ 'member-list--compact': compact }">
    <div class="member-list__toolbar">
      <el-input
        v-model="keyword"
        :prefix-icon="Search"
        :placeholder="t('channel.memberSearch')"
        clearable
        class="member-list__search"
        @keyup.enter="search"
        @clear="search"
      />
      <el-button @click="search">{{ t('common.search') }}</el-button>
    </div>

    <LoadingState v-if="loading && members.length === 0" :rows="3" />

    <ErrorState v-else-if="error && members.length === 0" :message="error" @retry="load(true)" />

    <EmptyState
      v-else-if="members.length === 0"
      compact
      :title="t('channel.memberNotFound')"
      :description="t('channel.memberNotFoundHint')"
    />

    <ul v-else class="member-items">
      <li v-for="member in members" :key="member.userId" class="member-item">
        <UserCardPopover :user="{ id: member.userId, username: member.username, nickname: member.nickname, avatarUrl: member.avatarUrl }" :channel-id="channelId">
          <button type="button" class="member-item__identity">
            <UserAvatar :src="member.avatarUrl" :name="memberName(member)" :size="32" />
            <span class="member-item__meta">
              <strong>{{ memberName(member) }}</strong>
              <small>@{{ member.username }}</small>
            </span>
          </button>
        </UserCardPopover>

        <div class="member-item__tags">
          <el-tag v-if="member.roleName" size="small" effect="plain" type="info">{{ member.roleName }}</el-tag>
          <el-tag v-if="isMuted(member)" size="small" type="warning" effect="plain">{{ t('channel.member.muted') }}</el-tag>
        </div>

        <small v-if="member.joinedAt" class="member-item__joined">
          {{
            compact
              ? formatRelativeTime(member.joinedAt)
              : t('channel.member.joinedAt', { time: formatDateTime(member.joinedAt) })
          }}
        </small>

        <div v-if="canAssignRole || canMute || canRemove" class="member-item__actions">
          <el-dropdown v-if="canAssignRole && roles.length > 0" trigger="click" @command="(command) => handleAssignCommand(member, command)">
            <el-button text size="small">{{ t('channel.member.role') }}</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-for="role in roles"
                  :key="role.id"
                  :command="role.id"
                  :disabled="role.id === member.roleId || isOwnerRow(member)"
                >
                  {{ role.name }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

          <el-dropdown v-if="canMute && !isMuted(member) && !isOwnerRow(member)" trigger="click" @command="(command) => handleMuteCommand(member, command)">
            <el-button text size="small">{{ t('channel.member.mute') }}</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="duration in mutedDurations" :key="duration.seconds" :command="duration.seconds">
                  {{ duration.label }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button v-if="canMute && isMuted(member)" text size="small" @click="unmute(member)">{{ t('channel.member.unmute') }}</el-button>

          <el-button
            v-if="canRemove && !isOwnerRow(member) && member.userId !== auth.currentUser?.id"
            text
            size="small"
            type="danger"
            @click="remove(member)"
          >{{ t('common.remove') }}</el-button>
        </div>
      </li>
    </ul>

    <div v-if="hasMore" class="member-list__more">
      <el-button text type="primary" :loading="loading" @click="loadMore">{{ t('channel.member.loadMore') }}</el-button>
    </div>
    <p v-else-if="members.length > 0" class="member-list__total">
      {{ t('channel.member.total', { n: total || members.length }) }}
    </p>
  </div>
</template>

<style scoped lang="scss">
.member-list__toolbar {
  display: flex;
  gap: var(--isles-space-2);
  margin-bottom: var(--isles-space-3);
}

.member-list__search {
  max-width: 320px;
}

.member-items {
  display: flex;
  flex-direction: column;
}

.member-item {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
  padding: var(--isles-space-2) 0;
  border-bottom: 1px solid var(--isles-border);

  &:last-child {
    border-bottom: none;
  }
}

.member-item__identity {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  padding: 0;
  background: transparent;
  border: none;
  cursor: pointer;
}

.member-item__meta {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.3;

  strong {
    font-size: var(--isles-font-sm);
  }

  small {
    color: var(--isles-text-muted);
    font-size: var(--isles-font-xs);
  }
}

.member-item__tags {
  display: flex;
  gap: var(--isles-space-1);
}

.member-item__joined {
  margin-left: auto;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.member-item__actions {
  display: flex;
  gap: var(--isles-space-1);
  align-items: center;
}

.member-list__more,
.member-list__total {
  padding-top: var(--isles-space-3);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
  text-align: center;
}

.member-list--compact {
  .member-item {
    padding: var(--isles-space-1) 0;
  }

  .member-item__joined {
    display: none;
  }
}
</style>
