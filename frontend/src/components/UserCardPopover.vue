<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Plus } from '@element-plus/icons-vue'
import { usersApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useRequireLogin } from '@/composables/useRequireLogin'
import { formatDateTime, genderLabel } from '@/utils/format'
import type { UserCard, UserSummary } from '@/types/models'
import UserAvatar from './UserAvatar.vue'

const props = withDefaults(
  defineProps<{
    user: UserSummary
    channelId?: string
    showChannelMeta?: boolean
  }>(),
  {
    channelId: '',
    showChannelMeta: true,
  },
)

const auth = useAuthStore()
const { requireLogin } = useRequireLogin()

const card = ref<UserCard | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
let loadedKey = ''

const isSelf = computed(() => Boolean(auth.currentUser && auth.currentUser.id === props.user.id))
const displayName = computed(() => props.user.nickname || props.user.username)
const profile = computed(() => card.value ?? props.user)

async function handleShow(): Promise<void> {
  const key = `${props.user.id}:${props.channelId}`
  if (loadedKey === key && card.value) return
  loadedKey = key
  loading.value = true
  error.value = null
  try {
    card.value = await usersApi.getUserCard(props.user.id, props.channelId || undefined)
  } catch (err) {
    error.value = resolveErrorMessage(err, '用户卡片加载失败')
  } finally {
    loading.value = false
  }
}

watch(
  () => props.user.id,
  () => {
    card.value = null
    loadedKey = ''
  },
)

function handleMessage(): void {
  if (!requireLogin('登录后可发送私信')) return
  ElMessage.info('私信功能将在 M2 上线')
}

function handleAddFriend(): void {
  if (!requireLogin('登录后可添加好友')) return
  ElMessage.info('好友功能将在 M2 上线')
}
</script>

<template>
  <el-popover
    trigger="click"
    :width="304"
    placement="bottom-start"
    popper-class="user-card-popper"
    @show="handleShow"
  >
    <template #reference>
      <slot />
    </template>

    <div class="user-card">
      <el-skeleton v-if="loading" :rows="3" animated />

      <template v-else>
        <div class="user-card__head">
          <UserAvatar :src="profile.avatarUrl" :name="displayName" :size="52" />
          <div class="user-card__identity">
            <strong class="user-card__name">{{ displayName }}</strong>
            <span class="user-card__username">@{{ profile.username }}</span>
            <el-tag v-if="profile.channelRole" size="small" effect="plain" type="info">
              {{ profile.channelRole.name }}
            </el-tag>
          </div>
        </div>

        <dl class="user-card__meta">
          <div v-if="genderLabel(profile.gender)">
            <dt>性别</dt>
            <dd>{{ genderLabel(profile.gender) }}</dd>
          </div>
          <div v-if="profile.region">
            <dt>地区</dt>
            <dd>{{ profile.region }}</dd>
          </div>
          <div v-if="showChannelMeta && channelId && card?.joinedAt">
            <dt>加入频道</dt>
            <dd>{{ formatDateTime(card.joinedAt) }}</dd>
          </div>
        </dl>

        <p v-if="error" class="user-card__error">{{ error }}</p>

        <div class="user-card__actions">
          <template v-if="!isSelf">
            <el-button type="primary" size="small" :icon="ChatDotRound" @click="handleMessage">私信</el-button>
            <el-button size="small" :icon="Plus" @click="handleAddFriend">加好友</el-button>
          </template>
          <span v-else class="muted">这是你自己</span>
        </div>
      </template>
    </div>
  </el-popover>
</template>

<style scoped lang="scss">
.user-card {
  min-height: 80px;
}

.user-card__head {
  display: flex;
  gap: var(--isles-space-3);
  align-items: flex-start;
}

.user-card__identity {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.user-card__name {
  font-size: var(--isles-font-md);
  line-height: var(--isles-line-tight);
}

.user-card__username {
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.user-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2) var(--isles-space-4);
  margin-top: var(--isles-space-3);
  padding-top: var(--isles-space-3);
  border-top: 1px solid var(--isles-border);

  div {
    display: flex;
    gap: var(--isles-space-1);
    font-size: var(--isles-font-xs);
  }

  dt {
    color: var(--isles-text-muted);
  }

  dd {
    margin: 0;
    color: var(--isles-text-secondary);
  }
}

.user-card__error {
  margin-top: var(--isles-space-2);
  color: var(--isles-warning);
  font-size: var(--isles-font-xs);
}

.user-card__actions {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  margin-top: var(--isles-space-3);
}
</style>
