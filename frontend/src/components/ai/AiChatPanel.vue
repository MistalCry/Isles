<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Delete, Picture, Promotion, VideoPause } from '@element-plus/icons-vue'
import AiMessageItem from './AiMessageItem.vue'
import AiSessionSidebar from './AiSessionSidebar.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { IMAGE_ACCEPT } from '@/constants/app'
import { useAiStore } from '@/stores/ai'
import { useAuthStore } from '@/stores/auth'
import {
  createConversation,
  deleteConversation,
  editAndResend,
  listConversations,
  listMessages,
  regenerateChat,
  renameConversation,
  stopMessage,
  streamChat,
  type AiConversation,
  type AiMessage,
  type AiScope,
  type AiSource,
  type StreamHandlers,
} from '@/api/ai'
import { AppError, resolveErrorMessage } from '@/api/client'
import { isPreviewUnsupported, uploadMedia, validateMediaFile } from '@/api/media'

const { t } = useI18n()

interface PendingImage {
  url: string
  name: string
}

const props = withDefaults(
  defineProps<{
    scope?: AiScope
    channelId?: string | null
    channelName?: string | null
    showSessionSidebar?: boolean
    compact?: boolean
    assistantName?: string | null
    assistantAvatarUrl?: string | null
  }>(),
  {
    scope: 'GLOBAL',
    channelId: null,
    channelName: null,
    showSessionSidebar: true,
    compact: false,
    assistantName: null,
    assistantAvatarUrl: null,
  },
)

const router = useRouter()
const aiStore = useAiStore()
const authStore = useAuthStore()
const {
  status: aiStatus,
  loading: aiLoading,
  error: aiError,
  configured,
  enabledModels,
  selectedModelId,
  selectedModel,
  deepThinking,
  canDeepThinking,
  canVision,
} = storeToRefs(aiStore)

const isAuthenticated = computed(() => authStore.isAuthenticated)
const assistantDisplayName = computed(() => props.assistantName || (props.scope === 'CHANNEL' ? t('ai.chat.assistant') : t('ai.chat.ask')))

const conversations = ref<AiConversation[]>([])
const activeConversation = ref<AiConversation | null>(null)
const conversationLoading = ref(false)
const conversationError = ref<string | null>(null)

const messages = ref<AiMessage[]>([])
const messageLoading = ref(false)
const messageError = ref<string | null>(null)
const nextMessageCursor = ref<string | null>(null)
const loadingOlder = ref(false)

const input = ref('')
const pendingImages = ref<PendingImage[]>([])
const uploading = ref(false)
/** 上传进度与失败重试 */
const uploadPercent = ref(0)
const failedUpload = ref<File | null>(null)
const streaming = ref(false)
const streamError = ref<string | null>(null)
const currentAssistantId = ref<string | null>(null)
const abortController = ref<AbortController | null>(null)
const scrollRef = ref<HTMLElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)

const examples = computed(() =>
  props.scope === 'CHANNEL'
    ? [t('ai.chat.suggestChannel1'), t('ai.chat.suggestChannel2'), t('ai.chat.suggestChannel3')]
    : [t('ai.chat.suggestGlobal1'), t('ai.chat.suggestGlobal2'), t('ai.chat.suggestGlobal3')],
)

const placeholder = computed(() =>
  props.scope === 'CHANNEL'
    ? t('ai.chat.askChannel', { name: assistantDisplayName.value })
    : t('ai.chat.placeholder'),
)

const composerDisabled = computed(() => !configured.value || !isAuthenticated.value || uploading.value)

const hasConversation = computed(() => activeConversation.value !== null)

onMounted(async () => {
  if (!isAuthenticated.value) return
  await aiStore.load()
  await loadConversations()
})

watch(
  () => [props.scope, props.channelId],
  async () => {
    conversations.value = []
    activeConversation.value = null
    messages.value = []
    nextMessageCursor.value = null
    if (isAuthenticated.value) await loadConversations()
  },
)

function goLogin(): void {
  void router.push({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
}

// ---------------------------------------------------------------------------
// 会话
// ---------------------------------------------------------------------------

async function loadConversations(): Promise<void> {
  if (!isAuthenticated.value) return
  conversationLoading.value = true
  conversationError.value = null
  try {
    const page = await listConversations({ scope: props.scope, channelId: props.channelId, limit: 30 })
    conversations.value = page.items
    if (page.items.length > 0) {
      const currentId = activeConversation.value?.id
      const target = page.items.some((item) => item.id === currentId) ? currentId! : page.items[0].id
      await selectConversation(target)
    }
  } catch (cause) {
    conversationError.value = resolveErrorMessage(cause, t('ai.chat.sessionsFailed'))
  } finally {
    conversationLoading.value = false
  }
}

async function refreshConversationsQuietly(): Promise<void> {
  if (!isAuthenticated.value) return
  try {
    const page = await listConversations({ scope: props.scope, channelId: props.channelId, limit: 30 })
    conversations.value = page.items
    if (activeConversation.value) {
      activeConversation.value = page.items.find((item) => item.id === activeConversation.value?.id) ?? activeConversation.value
    }
  } catch {
    // 静默刷新失败不打断对话
  }
}

async function selectConversation(conversationId: string): Promise<void> {
  if (streaming.value) stopStreaming()
  const target = conversations.value.find((item) => item.id === conversationId)
  if (!target) return
  activeConversation.value = target
  messages.value = []
  nextMessageCursor.value = null
  messageError.value = null
  streamError.value = null
  messageLoading.value = true
  try {
    const page = await listMessages(conversationId, { limit: 50 })
    messages.value = page.items
    nextMessageCursor.value = page.nextCursor
    scrollToBottom()
  } catch (cause) {
    messageError.value = resolveErrorMessage(cause, t('ai.chat.messagesFailed'))
  } finally {
    messageLoading.value = false
  }
}

async function createNewConversation(): Promise<void> {
  if (!isAuthenticated.value) {
    goLogin()
    return
  }
  if (streaming.value) stopStreaming()
  try {
    const conversation = await createConversation({
      scope: props.scope,
      channelId: props.channelId,
      modelConfigId: selectedModelId.value,
    })
    conversations.value = [conversation, ...conversations.value.filter((item) => item.id !== conversation.id)]
    activeConversation.value = conversation
    messages.value = []
    nextMessageCursor.value = null
    streamError.value = null
    input.value = ''
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('ai.chat.createFailed')))
  }
}

async function handleRename(conversationId: string, title: string): Promise<void> {
  try {
    const updated = await renameConversation(conversationId, title)
    conversations.value = conversations.value.map((item) => (item.id === updated.id ? updated : item))
    if (activeConversation.value?.id === updated.id) activeConversation.value = updated
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('ai.chat.renameFailed')))
  }
}

async function handleDelete(conversationId: string): Promise<void> {
  try {
    await deleteConversation(conversationId)
    conversations.value = conversations.value.filter((item) => item.id !== conversationId)
    if (activeConversation.value?.id === conversationId) {
      activeConversation.value = null
      messages.value = []
      nextMessageCursor.value = null
      if (conversations.value.length > 0) await selectConversation(conversations.value[0].id)
    }
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('ai.chat.deleteFailed')))
  }
}

async function loadOlderMessages(): Promise<void> {
  if (!activeConversation.value || !nextMessageCursor.value || loadingOlder.value) return
  loadingOlder.value = true
  const cursor = nextMessageCursor.value
  try {
    const page = await listMessages(activeConversation.value.id, { cursor, limit: 50 })
    messages.value = [...page.items, ...messages.value]
    nextMessageCursor.value = page.nextCursor
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('ai.chat.earlierFailed')))
  } finally {
    loadingOlder.value = false
  }
}

// ---------------------------------------------------------------------------
// 发送 / 流式
// ---------------------------------------------------------------------------

async function send(): Promise<void> {
  if (!isAuthenticated.value) {
    goLogin()
    return
  }
  if (!configured.value) {
    ElMessage.warning(aiStatus.value?.message ?? t('ai.chat.noModel'))
    return
  }
  if (streaming.value || uploading.value) return
  const content = input.value.trim()
  const imageUrls = pendingImages.value.map((item) => item.url)
  if (!content && imageUrls.length === 0) return

  const conversation = activeConversation.value ?? (await createConversationForSend())
  if (!conversation) return

  const optimistic: AiMessage = {
    id: `local-${Date.now()}`,
    conversationId: conversation.id,
    role: 'USER',
    content,
    reasoningContent: null,
    images: imageUrls,
    sources: [],
    model: null,
    tokenIn: null,
    tokenOut: null,
    status: 'COMPLETED',
    createdAt: new Date().toISOString(),
  }
  messages.value = [...messages.value, optimistic]
  input.value = ''
  pendingImages.value = []
  scrollToBottom()

  await runStream((handlers, signal) =>
    streamChat(
      conversation.id,
      {
        content,
        imageUrls,
        modelConfigId: selectedModelId.value,
        deepThinking: deepThinking.value,
      },
      handlers,
      signal,
    ),
  )
}

async function createConversationForSend(): Promise<AiConversation | null> {
  try {
    const conversation = await createConversation({
      scope: props.scope,
      channelId: props.channelId,
      modelConfigId: selectedModelId.value,
    })
    conversations.value = [conversation, ...conversations.value]
    activeConversation.value = conversation
    return conversation
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('ai.chat.conversationFailed')))
    return null
  }
}

async function regenerate(): Promise<void> {
  const conversation = activeConversation.value
  if (!conversation || streaming.value) return
  const lastUserIndex = findLastIndex(messages.value, (message) => message.role === 'USER')
  if (lastUserIndex < 0) return
  messages.value = messages.value.slice(0, lastUserIndex + 1)
  await runStream((handlers, signal) =>
    regenerateChat(
      conversation.id,
      { modelConfigId: selectedModelId.value, deepThinking: deepThinking.value },
      handlers,
      signal,
    ),
  )
}

async function handleEdit(message: AiMessage, content: string): Promise<void> {
  if (streaming.value || !activeConversation.value) return
  const index = messages.value.findIndex((item) => item.id === message.id)
  if (index < 0) return
  const updated: AiMessage = { ...message, content }
  messages.value = [...messages.value.slice(0, index), updated]
  await runStream((handlers, signal) =>
    editAndResend(
      message.id,
      { content, modelConfigId: selectedModelId.value, deepThinking: deepThinking.value },
      handlers,
      signal,
    ),
  )
}

type StreamStarter = (handlers: StreamHandlers, signal: AbortSignal) => Promise<void>

async function runStream(start: StreamStarter): Promise<void> {
  if (streaming.value) return
  streaming.value = true
  streamError.value = null
  currentAssistantId.value = null
  const controller = new AbortController()
  abortController.value = controller

  /** meta 里的关键词兜底来源：只在没有 sources 事件时回填（见 onDone） */
  let metaSources: AiSource[] = []
  /**
   * 本次流是否收到过 sources 事件。
   * 契约：sources 是唯一信号，后端每次必发；收到后即便数组为空也不再用 meta 兜底，
   * 否则工具模式下「检索为空」会被关键词来源污染成「错的来源」。
   */
  let sourcesEventReceived = false

  const handlers: StreamHandlers = {
    onMeta: (meta) => {
      metaSources = meta.sources ?? []
      currentAssistantId.value = meta.messageId
      const placeholder: AiMessage = {
        id: meta.messageId,
        conversationId: meta.conversationId,
        role: 'ASSISTANT',
        content: '',
        reasoningContent: null,
        images: [],
        // 工具模式下 meta.sources 是关键词兜底，先留空；真实来源由 sources 事件整体替换
        sources: [],
        model: meta.model,
        tokenIn: null,
        tokenOut: null,
        status: 'STREAMING',
        createdAt: new Date().toISOString(),
      }
      messages.value = [...messages.value, placeholder]
      scrollToBottom()
    },
    onSources: (payload) => {
      sourcesEventReceived = true
      const message = findMessage(currentAssistantId.value)
      if (!message) return
      // 整体替换而不是追加：多次工具轮次会重复推送，追加会出现重复条目
      message.sources = payload.sources
    },
    onDelta: (delta) => {
      const message = findMessage(currentAssistantId.value)
      if (!message) return
      message.content += delta
      if (isNearBottom()) scrollToBottom()
    },
    onReasoning: (delta) => {
      const message = findMessage(currentAssistantId.value)
      if (!message) return
      message.reasoningContent = (message.reasoningContent ?? '') + delta
      if (isNearBottom()) scrollToBottom()
    },
    onDone: (done) => {
      const message = findMessage(done.messageId)
      if (!message) return
      // 兜底只服务「没收到 sources 事件」的链路（未开工具调用 / 网络中断在半路）：
      // 一旦收到过事件，就以事件为准，哪怕它是空数组。
      if (!sourcesEventReceived && metaSources.length > 0) {
        message.sources = metaSources
      }
      message.status = done.status
      message.tokenIn = done.tokenIn ?? message.tokenIn
      message.tokenOut = done.tokenOut ?? message.tokenOut
    },
    onError: (error) => {
      streamError.value = error.message
    },
  }

  try {
    await start(handlers, controller.signal)
  } catch (cause) {
    if (controller.signal.aborted) {
      markStreamingMessage('ABORTED')
    } else {
      const message = cause instanceof AppError ? cause.message : resolveErrorMessage(cause, t('ai.chat.failed'))
      streamError.value = message
      ElMessage.error(message)
      markStreamingMessage('FAILED')
    }
  } finally {
    markStreamingMessage(null)
    streaming.value = false
    currentAssistantId.value = null
    abortController.value = null
    scrollToBottom()
    void refreshConversationsQuietly()
  }
}

function markStreamingMessage(status: AiMessage['status'] | null): void {
  const message = findMessage(currentAssistantId.value)
  if (!message || message.status !== 'STREAMING') return
  if (status) message.status = status
}

function stopStreaming(): void {
  const messageId = currentAssistantId.value
  abortController.value?.abort()
  if (messageId) {
    void stopMessage(messageId).catch(() => undefined)
  }
}

// ---------------------------------------------------------------------------
// 输入与附件
// ---------------------------------------------------------------------------

function onKeydown(event: Event): void {
  const keyboard = event as KeyboardEvent
  if (keyboard.isComposing) return
  if (keyboard.key === 'Enter' && !keyboard.shiftKey && !keyboard.ctrlKey && !keyboard.altKey) {
    keyboard.preventDefault()
    void send()
  }
}

function triggerUpload(): void {
  if (!canVision.value) {
    ElMessage.info(t('ai.chat.visionUnsupported'))
    return
  }
  fileInput.value?.click()
}

async function handleFileChange(event: Event): Promise<void> {
  const target = event.target as HTMLInputElement
  const files = Array.from(target.files ?? [])
  target.value = ''
  if (files.length === 0) return
  if (pendingImages.value.length + files.length > 4) {
    ElMessage.warning(t('ai.chat.imageLimit'))
    return
  }
  for (const file of files) {
    if (!file.type.startsWith('image/')) {
      ElMessage.warning(t('ai.chat.imageOnly'))
      continue
    }
    const invalid = validateMediaFile(file)
    if (invalid) {
      ElMessage.warning(invalid)
      continue
    }
    if (isPreviewUnsupported(file)) {
      ElMessage.info(t('media.heicNoPreview'))
    }
    await uploadOne(file)
  }
}

/** 单个文件上传：带百分比，失败时记住文件供「重试」 */
async function uploadOne(file: File): Promise<void> {
  uploading.value = true
  uploadPercent.value = 0
  failedUpload.value = null
  try {
    const media = await uploadMedia(file, (percent) => {
      uploadPercent.value = percent
    })
    if (media.url) pendingImages.value = [...pendingImages.value, { url: media.url, name: file.name }]
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('editor.imageUploadFailed')))
    failedUpload.value = file
  } finally {
    uploading.value = false
    uploadPercent.value = 0
  }
}

async function retryUpload(): Promise<void> {
  const file = failedUpload.value
  if (!file) return
  await uploadOne(file)
}

function removeImage(index: number): void {
  pendingImages.value = pendingImages.value.filter((_, itemIndex) => itemIndex !== index)
}

function pickExample(example: string): void {
  input.value = example
}

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

function findMessage(messageId: string | null): AiMessage | undefined {
  if (!messageId) return undefined
  return messages.value.find((message) => message.id === messageId)
}

function isLastAssistant(message: AiMessage): boolean {
  if (message.role !== 'ASSISTANT') return false
  const last = messages.value[messages.value.length - 1]
  return last?.id === message.id
}

function findLastIndex<T>(items: T[], predicate: (item: T) => boolean): number {
  for (let index = items.length - 1; index >= 0; index -= 1) {
    if (predicate(items[index])) return index
  }
  return -1
}

function isNearBottom(): boolean {
  const element = scrollRef.value
  if (!element) return true
  return element.scrollHeight - element.scrollTop - element.clientHeight < 120
}

function scrollToBottom(): void {
  void nextTick(() => {
    const element = scrollRef.value
    if (element) element.scrollTop = element.scrollHeight
  })
}

function onSelectModel(value: string | number | boolean | object): void {
  aiStore.selectModel(String(value))
}

function onToggleDeepThinking(value: boolean | string | number): void {
  aiStore.toggleDeepThinking(Boolean(value))
}

function retryAiStatus(): void {
  void aiStore.load(true)
}

function retryMessages(): void {
  if (activeConversation.value) void selectConversation(activeConversation.value.id)
}
</script>

<template>
  <div class="ai-chat" :class="{ 'ai-chat--compact': compact }">
    <AiSessionSidebar
      v-if="showSessionSidebar && !compact"
      :conversations="conversations"
      :active-id="activeConversation?.id ?? null"
      :loading="conversationLoading"
      :error="conversationError"
      @select="selectConversation"
      @create="createNewConversation"
      @rename="handleRename"
      @remove="handleDelete"
      @retry="loadConversations"
    />

    <section class="ai-chat__main">
      <div ref="scrollRef" class="ai-chat__messages" aria-live="polite">
        <div v-if="aiLoading && !aiStatus" class="ai-chat__state">
          <el-skeleton :rows="5" animated />
        </div>
        <ErrorState
          v-else-if="aiError"
          class="ai-chat__state"
          :message="aiError"
          @retry="retryAiStatus"
        />
        <EmptyState
          v-else-if="!isAuthenticated"
          class="ai-chat__state"
          :title="t('ai.guestTitle')"
          :description="t('ai.guestHint')"
        >
          <template #action>
            <el-button type="primary" @click="goLogin">{{ t('ai.chat.login') }}</el-button>
          </template>
        </EmptyState>
        <EmptyState
          v-else-if="!configured && !aiLoading"
          class="ai-chat__state"
          :title="t('ai.chat.noModel')"
          :description="aiStatus?.message ?? t('ai.chat.configureHint')"
        />
        <EmptyState
          v-else-if="!hasConversation && messages.length === 0"
          class="ai-chat__state"
          :title="t('ai.startFirst')"
          :description="scope === 'CHANNEL' ? t('ai.chat.channelScopeHint') : t('ai.chat.globalScopeHint')"
        >
          <template #action>
            <div class="ai-chat__examples">
              <button
                v-for="example in examples"
                :key="example"
                type="button"
                class="example-chip"
                @click="pickExample(example)"
              >
                {{ example }}
              </button>
            </div>
          </template>
        </EmptyState>
        <div v-else-if="messageLoading" class="ai-chat__state">
          <el-skeleton :rows="4" animated />
        </div>
        <ErrorState
          v-else-if="messageError"
          class="ai-chat__state"
          :message="messageError"
          @retry="retryMessages"
        />
        <template v-else>
          <div v-if="nextMessageCursor" class="ai-chat__more">
            <el-button link size="small" :loading="loadingOlder" @click="loadOlderMessages">{{ t('ai.chat.loadEarlier') }}</el-button>
          </div>
          <AiMessageItem
            v-for="message in messages"
            :key="message.id"
            :message="message"
            :streaming="streaming && message.id === currentAssistantId"
            :editable="message.role === 'USER'"
            :can-regenerate="isLastAssistant(message) && !streaming"
            :assistant-name="assistantDisplayName"
            :assistant-avatar-url="assistantAvatarUrl"
            @regenerate="regenerate"
            @edit="(content) => handleEdit(message, content)"
          />
        </template>
      </div>

      <footer class="ai-chat__composer">
        <el-alert
          v-if="streamError"
          class="ai-chat__alert"
          type="warning"
          :title="streamError"
          show-icon
          closable
          @close="streamError = null"
        />
        <div v-if="pendingImages.length > 0" class="ai-chat__attachments">
          <div v-for="(image, index) in pendingImages" :key="image.url" class="attachment">
            <img :src="image.url" :alt="image.name" />
            <button type="button" class="attachment__remove" :aria-label="t('ai.removeImage')" @click="removeImage(index)">
              <el-icon :size="12"><Delete /></el-icon>
            </button>
          </div>
        </div>

        <div class="composer" :class="{ 'is-disabled': composerDisabled }">
          <el-input
            v-model="input"
            class="composer__input"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 6 }"
            :placeholder="placeholder"
            :disabled="composerDisabled"
            maxlength="8000"
            @keydown="onKeydown"
          />
          <div class="composer__toolbar">
            <div class="composer__tools">
              <button
                type="button"
                class="tool-btn"
                :class="{ 'is-active': canVision }"
                :aria-label="t('ai.addImage')"
                :disabled="!canVision || uploading"
                @click="triggerUpload"
              >
                <el-icon :size="16"><Picture /></el-icon>
                <span v-if="uploading">{{ t('media.uploadPercent', { percent: uploadPercent }) }}</span>
              </button>
              <button
                v-if="failedUpload && !uploading"
                type="button"
                class="tool-btn"
                @click="retryUpload"
              >{{ t('media.uploadRetry') }}</button>
              <input
                ref="fileInput"
                type="file"
                :accept="IMAGE_ACCEPT"
                multiple
                class="visually-hidden"
                @change="handleFileChange"
              />
              <el-select
                v-if="enabledModels.length > 1"
                class="composer__model"
                :model-value="selectedModelId ?? undefined"
                size="small"
                :placeholder="t('ai.pickModel')"
                @change="onSelectModel"
              >
                <el-option
                  v-for="model in enabledModels"
                  :key="model.id"
                  :label="model.name"
                  :value="model.id"
                >
                  <span>{{ model.name }}</span>
                  <span class="model-option">
                    <el-tag v-if="model.visionEnabled" size="small" effect="plain">{{ t('ai.chat.visionTag') }}</el-tag>
                    <el-tag v-if="model.reasoningEnabled" size="small" effect="plain" type="warning">{{ t('ai.chat.thinkingTag') }}</el-tag>
                  </span>
                </el-option>
              </el-select>
              <label v-if="canDeepThinking" class="composer__switch">
                <el-switch :model-value="deepThinking" size="small" @change="onToggleDeepThinking" />
                <span>{{ t('ai.chat.deepThinking') }}</span>
              </label>
              <span v-else-if="selectedModel" class="muted composer__switch-hint">{{ t('ai.chat.noThinking') }}</span>
            </div>
            <el-button
              v-if="streaming"
              class="composer__send"
              type="danger"
              plain
              :icon="VideoPause"
              @click="stopStreaming"
            >
              {{ t('ai.chat.stop') }}
            </el-button>
            <el-button
              v-else
              class="composer__send"
              type="primary"
              :icon="Promotion"
              :disabled="composerDisabled"
              @click="send"
            >{{ t('common.send') }}</el-button>
          </div>
        </div>
        <p class="ai-chat__hint">
          <el-icon :size="12"><ChatDotRound /></el-icon>
          {{ t('ai.chat.disclaimer') }}
          <span v-if="selectedModel">{{ t('ai.chat.currentModel', { name: selectedModel.name }) }}</span>
        </p>
      </footer>
    </section>
  </div>
</template>

<style scoped lang="scss">
.ai-chat {
  display: flex;
  gap: var(--isles-space-4);
  height: 100%;
  min-height: 480px;
}

.ai-chat--compact {
  min-height: 320px;
}

.ai-chat__main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  height: 100%;
  overflow: hidden;
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
}

.ai-chat__messages {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: var(--isles-space-4);
  min-height: 0;
  padding: var(--isles-space-4);
  overflow-y: auto;
}

.ai-chat__state {
  margin: auto;
  width: 100%;
}

.ai-chat__more {
  display: flex;
  justify-content: center;
}

.ai-chat__examples {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  justify-content: center;
}

.example-chip {
  padding: 6px 12px;
  color: var(--isles-primary);
  font-size: var(--isles-font-sm);
  background: var(--isles-primary-soft);
  border: 1px solid transparent;
  border-radius: var(--isles-radius-pill);
  cursor: pointer;

  &:hover {
    border-color: var(--isles-primary);
  }
}

.ai-chat__composer {
  flex: none;
  padding: var(--isles-space-3) var(--isles-space-4) var(--isles-space-2);
  border-top: 1px solid var(--isles-border);
  background: var(--isles-surface);
}

.ai-chat__alert {
  margin-bottom: var(--isles-space-2);
}

.ai-chat__attachments {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  margin-bottom: var(--isles-space-2);
}

.attachment {
  position: relative;
  width: 56px;
  height: 56px;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    border: 1px solid var(--isles-border);
    border-radius: var(--isles-radius-sm);
  }
}

.attachment__remove {
  position: absolute;
  top: -6px;
  right: -6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  color: var(--isles-text-inverse);
  background: var(--isles-text-secondary);
  border: none;
  border-radius: 50%;
  cursor: pointer;
}

.composer {
  padding: var(--isles-space-2) var(--isles-space-3);
  background: var(--isles-surface-muted);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-md);
  transition: border-color 0.15s ease;

  &:focus-within {
    border-color: var(--isles-primary);
  }
}

.composer.is-disabled {
  opacity: 0.7;
}

.composer__input {
  :deep(.el-textarea__inner) {
    padding: 4px 0;
    background: transparent;
    box-shadow: none;
  }
}

.composer__toolbar {
  display: flex;
  gap: var(--isles-space-3);
  align-items: center;
  justify-content: space-between;
  margin-top: var(--isles-space-1);
}

.composer__tools {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
}

.tool-btn {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 4px 6px;
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-xs);
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-sm);
  cursor: pointer;

  &:hover:not(:disabled) {
    color: var(--isles-primary);
    background: var(--isles-hover);
  }

  &:disabled {
    color: var(--isles-text-muted);
    cursor: not-allowed;
  }
}

.composer__model {
  width: 150px;
}

.model-option {
  display: inline-flex;
  gap: 4px;
  float: right;
  margin-left: var(--isles-space-2);
}

.composer__switch {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-xs);
  cursor: pointer;
}

.composer__send {
  flex: none;
}

.ai-chat__hint {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
  justify-content: center;
  margin-top: var(--isles-space-2);
  color: var(--isles-text-muted);
  font-size: 11px;
  text-align: center;

  span {
    margin-left: var(--isles-space-2);
  }
}

@media (max-width: 900px) {
  .ai-chat {
    flex-direction: column;
    height: auto;
  }

  .ai-chat__main {
    min-height: 70vh;
  }
}
</style>
