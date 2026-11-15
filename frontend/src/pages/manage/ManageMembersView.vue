<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { channelsApi, manageApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import {
  CHANNEL_PERMISSIONS,
  hasChannelPermission,
  permissionLabel,
  resolveChannelPermissions,
} from '@/utils/channelPermissions'
import type { Board, Channel, ChannelRoleItem } from '@/types/models'
import ChannelMemberList from '@/components/channel/ChannelMemberList.vue'
import RoleEditorDialog from '@/components/manage/RoleEditorDialog.vue'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const { t } = useI18n()

const props = defineProps<{
  channel: Channel
  channelId: string
}>()

const auth = useAuthStore()
const activeTab = ref('members')
const roles = ref<ChannelRoleItem[]>([])
const boards = ref<Board[]>([])
const rolesLoading = ref(false)
const rolesError = ref<string | null>(null)
const memberReloadKey = ref(0)
const roleEditorOpen = ref(false)
const editingRole = ref<ChannelRoleItem | null>(null)

const permissions = computed(() => resolveChannelPermissions(props.channel, auth.currentUser?.id))
const canAssignRole = computed(() => hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.ROLE_MANAGE))
const canMute = computed(() => hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.MEMBER_MUTE))
const canRemove = computed(() => hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.MEMBER_REMOVE))
const canManageRoles = computed(() => hasChannelPermission(permissions.value, CHANNEL_PERMISSIONS.ROLE_MANAGE))

async function loadRoles(): Promise<void> {
  rolesLoading.value = true
  rolesError.value = null
  try {
    roles.value = await manageApi.getRoles(props.channelId)
  } catch (err) {
    rolesError.value = resolveErrorMessage(err, t('manage.members.rolesFailed'))
  } finally {
    rolesLoading.value = false
  }
}

async function loadBoards(): Promise<void> {
  try {
    boards.value = await channelsApi.getBoards(props.channelId)
  } catch {
    boards.value = []
  }
}

function openCreateRole(): void {
  editingRole.value = null
  roleEditorOpen.value = true
}

function openEditRole(role: ChannelRoleItem): void {
  if (role.isSystem) return
  editingRole.value = role
  roleEditorOpen.value = true
}

async function handleRoleSaved(): Promise<void> {
  await loadRoles()
  memberReloadKey.value += 1
}

async function deleteRole(role: ChannelRoleItem): Promise<void> {
  if (role.isSystem) return
  try {
    await ElMessageBox.confirm(
      t('manage.members.deleteRoleConfirm', { name: role.name }),
      t('manage.members.deleteRole'),
      {
      type: 'warning',
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel'),
    })
  } catch {
    return
  }
  try {
    await manageApi.deleteRole(props.channelId, role.id)
    roles.value = roles.value.filter((item) => item.id !== role.id)
    ElMessage.success(t('manage.members.roleDeleted'))
  } catch {
    // 拦截器已提示
  }
}

onMounted(() => {
  void loadRoles()
  void loadBoards()
})
</script>

<template>
  <section class="manage-section">
    <header class="manage-section__header">
      <div>
        <h2>{{ t('manage.nav.members') }}</h2>
        <p class="muted">{{ t('manage.members.subtitle') }}</p>
      </div>
      <el-button v-if="canManageRoles" type="primary" :icon="Plus" @click="openCreateRole">{{ t('manage.role.create') }}</el-button>
    </header>

    <el-tabs v-model="activeTab">
      <el-tab-pane :label="t('manage.members.list')" name="members">
        <ChannelMemberList
          :key="`members-${channelId}-${memberReloadKey}`"
          :channel-id="channelId"
          :roles="roles"
          :can-assign-role="canAssignRole"
          :can-mute="canMute"
          :can-remove="canRemove"
        />
      </el-tab-pane>

      <el-tab-pane :label="t('channel.member.role')" name="roles">
        <LoadingState v-if="rolesLoading && roles.length === 0" :rows="3" :tip="t('channel.settings.roleLoading')" />
        <ErrorState v-else-if="rolesError && roles.length === 0" :message="rolesError" @retry="loadRoles" />
        <EmptyState v-else-if="roles.length === 0" :title="t('channel.roleEmpty')" :description="t('channel.roleEmptyHint')" />

        <div v-else class="role-grid">
          <article v-for="role in roles" :key="role.id" class="role-card isles-card">
            <header class="role-card__head">
              <div>
                <strong>{{ role.name }}</strong>
                <el-tag v-if="role.isSystem" size="small" type="info" effect="plain">{{ t('common.system') }}</el-tag>
              </div>
              <div v-if="canManageRoles && !role.isSystem" class="role-card__actions">
                <el-button text size="small" @click="openEditRole(role)">{{ t('common.edit') }}</el-button>
                <el-button text size="small" type="danger" @click="deleteRole(role)">{{ t('common.delete') }}</el-button>
              </div>
            </header>
            <p class="muted role-card__desc">{{ role.description || t('common.noDescription') }}</p>
            <div class="role-card__permissions">
              <el-tag v-for="code in role.permissions" :key="code" size="small" effect="plain" type="info">
                {{ permissionLabel(code) }}
              </el-tag>
              <span v-if="role.permissions.length === 0" class="muted">{{ t('manage.members.noPermissions') }}</span>
            </div>
            <footer class="role-card__footer muted">
              <span v-if="role.memberCount !== undefined">
                {{ t('manage.members.roleMemberCount', { n: role.memberCount }) }}
              </span>
              <span v-if="role.scopeBoardId">
                {{
                  t('manage.members.roleBoardScope', {
                    name: boards.find((board) => board.id === role.scopeBoardId)?.name || role.scopeBoardId,
                  })
                }}
              </span>
            </footer>
          </article>
        </div>
      </el-tab-pane>
    </el-tabs>

    <RoleEditorDialog
      v-model="roleEditorOpen"
      :channel-id="channelId"
      :role="editingRole"
      :boards="boards"
      @saved="handleRoleSaved"
    />
  </section>
</template>

<style scoped lang="scss">
.manage-section__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--isles-space-3);
  margin-bottom: var(--isles-space-4);

  h2 {
    font-size: var(--isles-font-lg);
    font-weight: 600;
  }

  p {
    margin-top: 2px;
    font-size: var(--isles-font-xs);
  }
}

.role-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: var(--isles-space-3);
}

.role-card {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
  padding: var(--isles-space-4);
}

.role-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--isles-space-2);

  strong {
    font-size: var(--isles-font-md);
  }

  .el-tag {
    margin-left: var(--isles-space-1);
  }
}

.role-card__desc {
  font-size: var(--isles-font-xs);
}

.role-card__permissions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-1);
}

.role-card__footer {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-3);
  padding-top: var(--isles-space-2);
  margin-top: auto;
  font-size: var(--isles-font-xs);
  border-top: 1px solid var(--isles-border);
}
</style>
