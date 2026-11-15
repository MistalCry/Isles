<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Close } from '@element-plus/icons-vue'
import AiChatPanel from './AiChatPanel.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useAuthStore } from '@/stores/auth'
import { getAssistantInfo, type AssistantInfo } from '@/api/ai'
import { resolveErrorMessage } from '@/api/client'

const props = withDefaults(
  defineProps<{
    channelId: string
    channelName?: string | null
    /** 父组件可用 v-model 控制显示；也可由父组件 v-if 控制 */
    modelValue?: boolean
  }>(),
  { channelName: null, modelValue: true },
)

const emit = defineEmits<{ 'update:modelValue': [value: boolean]; close: [] }>()

const router = useRouter()
const authStore = useAuthStore()

const info = ref<AssistantInfo | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

const isAuthenticated = computed(() => authStore.isAuthenticated)
const available = computed(() => info.value?.configured === true && info.value?.enabled === true)
const assistantName = computed(() => info.value?.nickname || '频道助手')
const assistantAvatar = computed(() => info.value?.avatarUrl ?? null)

async function loadInfo(): Promise<void> {
  if (!props.channelId || !isAuthenticated.value) return
  loading.value = true
  error.value = null
  try {
    info.value = await getAssistantInfo(props.channelId)
  } catch (cause) {
    error.value = resolveErrorMessage(cause, '频道助手信息加载失败')
  } finally {
    loading.value = false
  }
}

function close(): void {
  emit('update:modelValue', false)
  emit('close')
}

function goLogin(): void {
  void router.push({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
}

onMounted(loadInfo)
watch(
  () => [props.channelId, isAuthenticated.value],
  () => {
    info.value = null
    void loadInfo()
  },
)
</script>

<template>
  <section class="channel-assistant" aria-label="频道助手">
    <header class="channel-assistant__head">
      <div class="channel-assistant__identity">
        <UserAvatar :src="assistantAvatar" :name="assistantName" :size="28" square />
        <div class="channel-assistant__meta">
          <strong>{{ assistantName }}</strong>
          <small>{{ info?.bio || '基于本频道内容回答' }}</small>
        </div>
        <el-tag v-if="available" size="small" type="success" effect="plain">在线</el-tag>
      </div>
      <div class="channel-assistant__actions">
        <button type="button" class="icon-btn" aria-label="返回频道" @click="close">
          <el-icon :size="15"><ArrowLeft /></el-icon>
        </button>
        <button type="button" class="icon-btn" aria-label="关闭频道助手" @click="close">
          <el-icon :size="15"><Close /></el-icon>
        </button>
      </div>
    </header>

    <div class="channel-assistant__body">
      <el-skeleton v-if="loading" :rows="4" animated />
      <EmptyState
        v-else-if="!isAuthenticated"
        compact
        title="登录后使用频道助手"
        description="频道助手基于本频道内容回答问题。"
      >
        <template #action>
          <el-button type="primary" size="small" @click="goLogin">去登录</el-button>
        </template>
      </EmptyState>
      <EmptyState v-else-if="error" compact title="频道助手暂不可用" :description="error" />
      <EmptyState
        v-else-if="!available"
        compact
        title="频道主还没有开启频道助手"
        :description="
          info?.configured
            ? '频道助手已创建但处于关闭状态，频道主可在管理中心的「频道助手」中开启。'
            : '频道主可在管理中心的「频道助手」中配置助手身份与自动任务。'
        "
      />
      <AiChatPanel
        v-else
        :channel-id="channelId"
        :channel-name="channelName"
        scope="CHANNEL"
        compact
        :show-session-sidebar="false"
        :assistant-name="assistantName"
        :assistant-avatar-url="assistantAvatar"
      />
    </div>
  </section>
</template>

<style scoped lang="scss">
.channel-assistant {
  display: flex;
  flex-direction: column;
  width: 380px;
  max-width: calc(100vw - 32px);
  height: 560px;
  max-height: calc(100vh - 120px);
  overflow: hidden;
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
  box-shadow: var(--isles-shadow-lg);
}

.channel-assistant__head {
  display: flex;
  flex: none;
  gap: var(--isles-space-2);
  align-items: center;
  justify-content: space-between;
  padding: var(--isles-space-3);
  border-bottom: 1px solid var(--isles-border);
  background: var(--isles-surface-muted);
}

.channel-assistant__identity {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  min-width: 0;
}

.channel-assistant__meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.3;

  strong {
    overflow: hidden;
    font-size: var(--isles-font-sm);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  small {
    overflow: hidden;
    color: var(--isles-text-muted);
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.channel-assistant__actions {
  display: flex;
  gap: 2px;
  flex: none;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  color: var(--isles-text-muted);
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-sm);
  cursor: pointer;

  &:hover {
    color: var(--isles-primary);
    background: var(--isles-hover);
  }
}

.channel-assistant__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  padding: var(--isles-space-2);
  overflow: hidden;

  :deep(.ai-chat) {
    height: 100%;
    min-height: 0;
  }

  :deep(.ai-chat__main) {
    border: none;
  }

  :deep(.ai-chat__messages) {
    padding: var(--isles-space-2);
  }

  :deep(.ai-chat__composer) {
    padding: var(--isles-space-2);
  }
}
</style>
