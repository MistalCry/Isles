<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CopyDocument, Link, Refresh } from '@element-plus/icons-vue'
import { manageApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { copyText } from '@/utils/clipboard'
import { absoluteUrl, formatDateTime } from '@/utils/format'
import type { InviteLinkItem, JoinRequestItem } from '@/types/models'
import UserAvatar from '../UserAvatar.vue'
import LoadingState from '../LoadingState.vue'
import ErrorState from '../ErrorState.vue'
import EmptyState from '../EmptyState.vue'

const props = withDefaults(
  defineProps<{
    channelId: string
    canManage?: boolean
    canApprove?: boolean
  }>(),
  {
    canManage: false,
    canApprove: false,
  },
)

const invites = ref<InviteLinkItem[]>([])
const invitesLoading = ref(false)
const invitesError = ref<string | null>(null)
const generatedInvite = ref<InviteLinkItem | null>(null)
const creating = ref(false)
const joinRequests = ref<JoinRequestItem[]>([])
const requestsLoading = ref(false)
const requestsError = ref<string | null>(null)
const processingId = ref('')

const inviteForm = reactive({
  requiresApproval: false,
  expiresInSeconds: 7 * 24 * 3600,
  maxUses: 0,
})

const expiryOptions = [
  { label: '1 小时', value: 3600 },
  { label: '1 天', value: 86400 },
  { label: '7 天', value: 7 * 86400 },
  { label: '30 天', value: 30 * 86400 },
]

const maxUsesOptions = [
  { label: '不限次数', value: 0 },
  { label: '1 次', value: 1 },
  { label: '5 次', value: 5 },
  { label: '10 次', value: 10 },
  { label: '50 次', value: 50 },
]

async function loadInvites(): Promise<void> {
  if (!props.canManage) return
  invitesLoading.value = true
  invitesError.value = null
  try {
    invites.value = await manageApi.getInvites(props.channelId)
  } catch (err) {
    invitesError.value = resolveErrorMessage(err, '邀请链接加载失败')
  } finally {
    invitesLoading.value = false
  }
}

async function loadJoinRequests(): Promise<void> {
  if (!props.canApprove) return
  requestsLoading.value = true
  requestsError.value = null
  try {
    joinRequests.value = await manageApi.getJoinRequests(props.channelId, 'PENDING')
  } catch (err) {
    requestsError.value = resolveErrorMessage(err, '加入申请加载失败')
  } finally {
    requestsLoading.value = false
  }
}

async function createInvite(): Promise<void> {
  if (!props.canManage) return
  creating.value = true
  try {
    const invite = await manageApi.createInvite(props.channelId, {
      requiresApproval: inviteForm.requiresApproval,
      expiresInSeconds: inviteForm.expiresInSeconds,
      maxUses: inviteForm.maxUses || undefined,
    })
    generatedInvite.value = invite
    invites.value = [invite, ...invites.value]
    ElMessage.success('邀请链接已生成')
  } catch {
    // 拦截器已提示
  } finally {
    creating.value = false
  }
}

const INVITE_STATUS_TEXT: Record<string, string> = {
  REVOKED: '已撤销',
  EXPIRED: '已过期',
  EXHAUSTED: '次数用尽',
}

function inviteStatusText(status: string): string {
  return INVITE_STATUS_TEXT[status] ?? status
}

function inviteLink(invite: InviteLinkItem): string {
  if (invite.url) return absoluteUrl(invite.url)
  if (invite.token) return absoluteUrl(`/invites/${invite.token}`)
  return ''
}

async function copyInvite(invite: InviteLinkItem): Promise<void> {
  const link = inviteLink(invite)
  if (!link) {
    ElMessage.warning('该邀请链接不包含可复制的 token，请重新生成')
    return
  }
  const copied = await copyText(link)
  if (copied) {
    ElMessage.success('邀请链接已复制')
  } else {
    ElMessage.warning('复制失败，请手动复制')
  }
}

async function revokeInvite(invite: InviteLinkItem): Promise<void> {
  try {
    await ElMessageBox.confirm('撤销后该邀请链接将立即失效，确认撤销？', '撤销邀请链接', {
      type: 'warning',
      confirmButtonText: '撤销',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await manageApi.revokeInvite(props.channelId, invite.id)
    invites.value = invites.value.filter((item) => item.id !== invite.id)
    if (generatedInvite.value?.id === invite.id) generatedInvite.value = null
    ElMessage.success('邀请链接已撤销')
  } catch {
    // 拦截器已提示
  }
}

async function approve(request: JoinRequestItem): Promise<void> {
  if (processingId.value) return
  processingId.value = request.id
  try {
    await manageApi.approveJoinRequest(props.channelId, request.id)
    joinRequests.value = joinRequests.value.filter((item) => item.id !== request.id)
    ElMessage.success('已通过加入申请')
  } catch {
    // 拦截器已提示
  } finally {
    processingId.value = ''
  }
}

async function reject(request: JoinRequestItem): Promise<void> {
  if (processingId.value) return
  processingId.value = request.id
  try {
    await manageApi.rejectJoinRequest(props.channelId, request.id)
    joinRequests.value = joinRequests.value.filter((item) => item.id !== request.id)
    ElMessage.success('已拒绝加入申请')
  } catch {
    // 拦截器已提示
  } finally {
    processingId.value = ''
  }
}

function requestName(request: JoinRequestItem): string {
  return request.nickname || request.username || '岛民'
}

onMounted(() => {
  void loadInvites()
  void loadJoinRequests()
})

watch(
  () => [props.channelId, props.canManage, props.canApprove],
  () => {
    void loadInvites()
    void loadJoinRequests()
  },
)
</script>

<template>
  <section class="invite-section">
    <div v-if="canManage" class="invite-section__create">
      <h3 class="invite-section__title">生成邀请链接</h3>
      <el-form label-position="top" class="invite-section__form" @submit.prevent>
        <el-form-item label="加入方式">
          <el-radio-group v-model="inviteForm.requiresApproval">
            <el-radio :value="false">直接加入</el-radio>
            <el-radio :value="true">需要审批</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="有效期">
          <el-select v-model="inviteForm.expiresInSeconds" class="invite-section__select">
            <el-option v-for="option in expiryOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="使用次数">
          <el-select v-model="inviteForm.maxUses" class="invite-section__select">
            <el-option v-for="option in maxUsesOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-button type="primary" :icon="Link" :loading="creating" @click="createInvite">生成链接</el-button>
      </el-form>

      <div v-if="generatedInvite" class="invite-section__generated">
        <div>
          <strong>新链接（仅本次可见完整 token）</strong>
          <p class="invite-section__link">{{ inviteLink(generatedInvite) }}</p>
          <p class="muted">
            {{ generatedInvite.requiresApproval ? '需审批' : '直接加入' }} ·
            {{ generatedInvite.expiresAt ? `过期时间 ${formatDateTime(generatedInvite.expiresAt)}` : '长期有效' }}
          </p>
        </div>
        <el-button :icon="CopyDocument" @click="copyInvite(generatedInvite)">复制</el-button>
      </div>

      <div class="invite-section__list-head">
        <h4>已有邀请链接</h4>
        <el-button text :icon="Refresh" @click="loadInvites">刷新</el-button>
      </div>

      <LoadingState v-if="invitesLoading && invites.length === 0" :rows="2" />
      <ErrorState v-else-if="invitesError && invites.length === 0" :message="invitesError" @retry="loadInvites" />
      <EmptyState v-else-if="invites.length === 0" compact title="还没有邀请链接" description="生成链接后邀请岛民加入频道。" />
      <ul v-else class="invite-section__invites">
        <li v-for="invite in invites" :key="invite.id" class="invite-row">
          <div class="invite-row__main">
            <el-tag size="small" :type="invite.requiresApproval ? 'warning' : 'success'" effect="plain">
              {{ invite.requiresApproval ? '需审批' : '直接加入' }}
            </el-tag>
            <el-tag v-if="invite.status && invite.status !== 'ACTIVE'" size="small" type="info" effect="plain">
              {{ inviteStatusText(invite.status) }}
            </el-tag>
            <span class="muted">
              {{ invite.expiresAt ? `过期 ${formatDateTime(invite.expiresAt)}` : '长期有效' }} ·
              已用 {{ invite.usedCount ?? 0 }}{{ invite.maxUses ? ` / ${invite.maxUses}` : '' }}
            </span>
          </div>
          <div class="invite-row__actions">
            <el-button v-if="invite.token || invite.url" text size="small" :icon="CopyDocument" @click="copyInvite(invite)">复制</el-button>
            <el-button
              v-if="!invite.status || invite.status === 'ACTIVE'"
              text
              size="small"
              type="danger"
              @click="revokeInvite(invite)"
            >
              撤销
            </el-button>
          </div>
        </li>
      </ul>
    </div>

    <div v-if="canApprove" class="invite-section__requests">
      <div class="invite-section__list-head">
        <h4>加入审批</h4>
        <el-button text :icon="Refresh" @click="loadJoinRequests">刷新</el-button>
      </div>
      <LoadingState v-if="requestsLoading && joinRequests.length === 0" :rows="2" />
      <ErrorState v-else-if="requestsError && joinRequests.length === 0" :message="requestsError" @retry="loadJoinRequests" />
      <EmptyState v-else-if="joinRequests.length === 0" compact title="暂无待审批申请" />
      <ul v-else class="invite-section__request-list">
        <li v-for="request in joinRequests" :key="request.id" class="request-row">
          <UserAvatar :src="request.avatarUrl" :name="requestName(request)" :size="30" />
          <div class="request-row__meta">
            <strong>{{ requestName(request) }}</strong>
            <small class="muted">@{{ request.username }} · {{ formatDateTime(request.createdAt) }}</small>
          </div>
          <div class="request-row__actions">
            <el-button size="small" type="primary" :loading="processingId === request.id" @click="approve(request)">
              通过
            </el-button>
            <el-button size="small" :disabled="processingId === request.id" @click="reject(request)">拒绝</el-button>
          </div>
        </li>
      </ul>
    </div>
  </section>
</template>

<style scoped lang="scss">
.invite-section__title {
  margin-bottom: var(--isles-space-3);
  font-size: var(--isles-font-md);
  font-weight: 600;
}

.invite-section__form {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-3);
  align-items: flex-end;

  :deep(.el-form-item) {
    margin-bottom: 0;
  }
}

.invite-section__select {
  width: 150px;
}

.invite-section__generated {
  display: flex;
  gap: var(--isles-space-3);
  align-items: center;
  justify-content: space-between;
  padding: var(--isles-space-3);
  margin-top: var(--isles-space-4);
  background: var(--isles-primary-soft);
  border-radius: var(--isles-radius-md);

  .muted {
    font-size: var(--isles-font-xs);
  }
}

.invite-section__link {
  margin: 2px 0;
  font-size: var(--isles-font-xs);
  word-break: break-all;
}

.invite-section__list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: var(--isles-space-4) 0 var(--isles-space-2);

  h4 {
    font-size: var(--isles-font-sm);
    font-weight: 600;
  }
}

.invite-section__invites,
.invite-section__request-list {
  display: flex;
  flex-direction: column;
}

.invite-row,
.request-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
  padding: var(--isles-space-2) 0;
  border-bottom: 1px solid var(--isles-border);
}

.invite-row__main {
  flex: 1;
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
  font-size: var(--isles-font-xs);
}

.invite-row__actions,
.request-row__actions {
  display: flex;
  gap: var(--isles-space-1);
}

.request-row__meta {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.35;

  strong {
    font-size: var(--isles-font-sm);
  }
}

.invite-section__requests {
  margin-top: var(--isles-space-5);
  padding-top: var(--isles-space-4);
  border-top: 1px solid var(--isles-border);
}
</style>
