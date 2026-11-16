<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, ref } from 'vue'
import { RefreshRight, EditPen, ArrowDown, ArrowRight } from '@element-plus/icons-vue'
import type { AiMessage } from '@/api/ai'
import { formatRelativeTime } from '@/utils/format'
import UserAvatar from '@/components/UserAvatar.vue'
import AiSourceList from './AiSourceList.vue'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    message: AiMessage
    streaming?: boolean
    editable?: boolean
    canRegenerate?: boolean
    assistantName?: string | null
    assistantAvatarUrl?: string | null
  }>(),
  {
    streaming: false,
    editable: false,
    canRegenerate: false,
    assistantName: '',
    assistantAvatarUrl: null,
  },
)

const emit = defineEmits<{ regenerate: []; edit: [content: string] }>()

const editing = ref(false)
const draft = ref('')
const reasoningOpen = ref(false)

const isUser = computed(() => props.message.role === 'USER')
const isFailed = computed(() => props.message.status === 'FAILED')
const isAborted = computed(() => props.message.status === 'ABORTED')
const showTyping = computed(
  () => props.streaming && !props.message.content && !props.message.reasoningContent,
)
const reasoning = computed(() => props.message.reasoningContent?.trim() ?? '')
const hasContent = computed(() => Boolean(props.message.content?.trim()))
/** 后端提示（如切换无视觉模型后忽略历史图片），仅助手消息展示 */
const notice = computed(() => (isUser.value ? '' : (props.message.notice ?? '').trim()))

function beginEdit(): void {
  if (!props.editable || props.streaming) return
  draft.value = props.message.content
  editing.value = true
}

function cancelEdit(): void {
  editing.value = false
  draft.value = ''
}

function submitEdit(): void {
  const content = draft.value.trim()
  if (!content) return
  editing.value = false
  emit('edit', content)
}
</script>

<template>
  <article class="ai-message" :class="[isUser ? 'ai-message--user' : 'ai-message--assistant']">
    <UserAvatar
      v-if="!isUser"
      class="ai-message__avatar"
      :src="assistantAvatarUrl"
      :name="assistantName || t('ai.chat.ask')"
      :size="32"
      square
    />

    <div class="ai-message__main">
      <header class="ai-message__head">
        <span class="ai-message__author">{{ isUser ? t('ai.message.me') : assistantName || t('ai.chat.ask') }}</span>
        <span class="ai-message__time">{{ formatRelativeTime(message.createdAt) }}</span>
        <el-tag v-if="isAborted" size="small" type="warning" effect="plain">{{ t('ai.message.stopped') }}</el-tag>
        <el-tag v-if="isFailed" size="small" type="danger" effect="plain">{{ t('ai.message.failed') }}</el-tag>
        <el-tag v-if="message.model" size="small" effect="plain">{{ message.model }}</el-tag>
      </header>

      <p v-if="notice" class="ai-message__notice">{{ notice }}</p>

      <div v-if="!isUser && reasoning" class="ai-message__reasoning">
        <button type="button" class="reasoning-toggle" @click="reasoningOpen = !reasoningOpen">
          <el-icon :size="12"><ArrowDown v-if="reasoningOpen" /><ArrowRight v-else /></el-icon>{{ t('ai.thinkingProcess') }}</button>
        <pre v-if="reasoningOpen" class="reasoning-content">{{ reasoning }}</pre>
      </div>

      <div v-if="editing" class="ai-message__editor">
        <el-input v-model="draft" type="textarea" :rows="3" maxlength="8000" show-word-limit />
        <div class="ai-message__editor-actions">
          <el-button size="small" @click="cancelEdit">{{ t('common.cancel') }}</el-button>
          <el-button size="small" type="primary" @click="submitEdit">{{ t('ai.message.saveAndRetry') }}</el-button>
        </div>
      </div>
      <div v-else class="ai-message__bubble">
        <p v-if="showTyping" class="ai-message__typing" :aria-label="t('ai.typing')">
          <span /><span /><span />
        </p>
        <p v-else-if="hasContent" class="ai-message__content">{{ message.content }}</p>
        <p v-else-if="isFailed" class="ai-message__empty">{{ t('ai.message.failedHint') }}</p>
        <p v-else-if="isAborted" class="ai-message__empty">{{ t('ai.message.stoppedHint') }}</p>
        <p v-else class="ai-message__empty">{{ t('ai.message.empty') }}</p>

        <div v-if="message.images?.length" class="ai-message__images">
          <img
            v-for="(image, index) in message.images"
            :key="`${message.id}-img-${index}`"
            :src="image"
            :alt="t('ai.attachedImage', { n: index + 1 })"
            class="ai-message__image"
            loading="lazy"
          />
        </div>
      </div>

      <AiSourceList v-if="!isUser && message.sources?.length" :sources="message.sources" compact />

      <footer v-if="!isUser && !streaming" class="ai-message__actions">
        <el-button v-if="canRegenerate" link size="small" :icon="RefreshRight" @click="emit('regenerate')">{{ t('ai.regenerate') }}</el-button>
        <span v-if="message.tokenOut" class="ai-message__tokens">{{ t('ai.tokenUsage', { n: message.tokenOut }) }}</span>
      </footer>

      <footer v-if="isUser && !streaming && editable" class="ai-message__actions ai-message__actions--user">
        <el-button link size="small" :icon="EditPen" @click="beginEdit">{{ t('common.edit') }}</el-button>
      </footer>
    </div>
  </article>
</template>

<style scoped lang="scss">
.ai-message {
  display: flex;
  gap: var(--isles-space-3);
  align-items: flex-start;
}

.ai-message--user {
  justify-content: flex-end;
}

.ai-message__avatar {
  margin-top: 2px;
}

.ai-message__main {
  max-width: 86%;
  min-width: 0;
}

.ai-message--user .ai-message__main {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.ai-message__head {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
  margin-bottom: 4px;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.ai-message__author {
  color: var(--isles-text-secondary);
  font-weight: 600;
}

.ai-message__bubble {
  padding: var(--isles-space-3) var(--isles-space-4);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
  box-shadow: var(--isles-shadow-sm);
}

.ai-message--user .ai-message__bubble {
  color: var(--isles-text-inverse);
  background: var(--isles-primary);
  border-color: var(--isles-primary);
}

.ai-message__content {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
}

.ai-message__empty {
  color: var(--isles-text-muted);
  font-size: var(--isles-font-sm);
}

.ai-message--user .ai-message__empty {
  color: rgba(255, 255, 255, 0.8);
}

.ai-message__typing {
  display: flex;
  gap: 4px;
  padding: 4px 0;

  span {
    width: 6px;
    height: 6px;
    background: var(--isles-text-muted);
    border-radius: 50%;
    animation: typing-bounce 1s infinite ease-in-out;

    &:nth-child(2) {
      animation-delay: 0.15s;
    }

    &:nth-child(3) {
      animation-delay: 0.3s;
    }
  }
}

@keyframes typing-bounce {
  0%,
  80%,
  100% {
    opacity: 0.35;
    transform: translateY(0);
  }

  40% {
    opacity: 1;
    transform: translateY(-3px);
  }
}

.ai-message__images {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  margin-top: var(--isles-space-2);
}

.ai-message__image {
  width: 120px;
  height: 120px;
  object-fit: cover;
  border-radius: var(--isles-radius-sm);
  border: 1px solid var(--isles-border);
}

.ai-message__notice {
  padding: var(--isles-space-2) var(--isles-space-3);
  margin-bottom: var(--isles-space-2);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-xs);
  background: var(--isles-surface-muted);
  border-left: 3px solid var(--isles-warning, #f59f00);
  border-radius: var(--isles-radius-sm);
}

.ai-message__reasoning {
  padding: var(--isles-space-2) var(--isles-space-3);
  margin-bottom: var(--isles-space-2);
  background: var(--isles-surface-muted);
  border: 1px dashed var(--isles-border-strong);
  border-radius: var(--isles-radius-md);
}

.reasoning-toggle {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
  background: transparent;
  border: none;
  cursor: pointer;
}

.reasoning-content {
  margin-top: var(--isles-space-2);
  color: var(--isles-text-secondary);
  font-family: inherit;
  font-size: var(--isles-font-xs);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.ai-message__editor {
  width: 100%;
  min-width: 320px;
}

.ai-message__editor-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--isles-space-2);
  margin-top: var(--isles-space-2);
}

.ai-message__actions {
  display: flex;
  gap: var(--isles-space-3);
  align-items: center;
  margin-top: var(--isles-space-1);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.ai-message__actions--user {
  justify-content: flex-end;
}

.ai-message__tokens {
  color: var(--isles-text-muted);
  font-size: 11px;
}
</style>
