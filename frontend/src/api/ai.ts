import { t } from '@/i18n'
import { AppError, API_BASE_URL, http } from './client'
import { getAccessToken } from './token'

/**
 * AI 能力接口封装（docs/03 §4.11 / §4.17、docs/05 待细化项）。
 *
 * <p>流式回答使用原生 fetch 读取 SSE（axios 不支持浏览器流式响应），401 时复用
 * auth store 的 refresh 单飞逻辑重试一次；其余接口复用 `client.ts` 的鉴权与错误信封。
 * SSE 事件命名：meta / delta / reasoning / done / error。
 */

export type AiScope = 'GLOBAL' | 'CHANNEL'
export type AiMessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM'
export type AiMessageStatus = 'COMPLETED' | 'STREAMING' | 'ABORTED' | 'FAILED'
export type AiTaskType =
  | 'DAILY_SUMMARY'
  | 'WEEKLY_SUMMARY'
  | 'MONTHLY_CONTRIBUTORS'
  | 'AUTO_POST'
  | 'AUTO_COMMENT'
  | 'AUTO_LIKE'

export interface AiModel {
  id: string
  name: string
  baseUrl: string
  model: string
  visionEnabled: boolean
  reasoningEnabled: boolean
  thinkingMode?: 'REASONING_EFFORT' | 'CHAT_TEMPLATE' | 'BOTH' | 'NONE'
  isDefault: boolean
  enabled: boolean
  timeoutSeconds?: number | null
  maxTokens?: number | null
  apiKeyConfigured: boolean
  apiKeyMasked?: string | null
  createdAt?: string | null
}

export interface AiStatus {
  configured: boolean
  enabledModelCount: number
  defaultModelName?: string | null
  defaultModel?: string | null
  message: string
}

export interface AiSource {
  id: string
  title: string
  channelId?: string | null
  channelName?: string | null
  excerpt?: string | null
  url: string
}

export interface AiConversation {
  id: string
  scope: AiScope
  channelId?: string | null
  title: string
  modelConfigId?: string | null
  messageCount: number
  lastMessageAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface AiMessage {
  id: string
  conversationId: string
  role: AiMessageRole
  content: string
  reasoningContent?: string | null
  images: string[]
  sources: AiSource[]
  model?: string | null
  tokenIn?: number | null
  tokenOut?: number | null
  status: AiMessageStatus
  createdAt?: string | null
}

export interface StreamMeta {
  conversationId: string
  messageId: string
  model: string
  sources: AiSource[]
  deepThinking: boolean
}

export interface StreamDone {
  messageId: string
  aborted: boolean
  status: AiMessageStatus
  tokenIn?: number | null
  tokenOut?: number | null
}

export interface StreamError {
  code: string
  message: string
}

export interface StreamHandlers {
  onMeta?: (meta: StreamMeta) => void
  onDelta?: (content: string) => void
  onReasoning?: (content: string) => void
  onDone?: (done: StreamDone) => void
  onError?: (error: StreamError) => void
}

export interface SendMessagePayload {
  content?: string
  imageUrls?: string[]
  modelConfigId?: string | null
  deepThinking?: boolean
  regenerate?: boolean
}

export interface AssistantInfo {
  channelId: string
  configured: boolean
  enabled: boolean
  assistantUserId?: string | null
  nickname?: string | null
  avatarUrl?: string | null
  bio?: string | null
  persona?: string | null
}

export interface AssistantSettings {
  channelId: string
  assistantUserId?: string | null
  nickname: string
  avatarUrl?: string | null
  coverUrl?: string | null
  bio?: string | null
  persona?: string | null
  enabled: boolean
  summaryDailyEnabled: boolean
  summaryWeeklyEnabled: boolean
  monthlyContributorsEnabled: boolean
  autoPostEnabled: boolean
  autoCommentEnabled: boolean
  autoLikeEnabled: boolean
  /** 有人回复助手评论时自动接话 */
  autoReplyEnabled: boolean
  dailySummaryHour: number
  autoPostPerDay: number
  autoCommentPerDay: number
  autoLikePerDay: number
  updatedAt?: string | null
}

export interface AssistantSettingsPayload {
  nickname?: string
  avatarUrl?: string | null
  coverUrl?: string | null
  bio?: string | null
  persona?: string | null
  enabled: boolean
  summaryDailyEnabled: boolean
  summaryWeeklyEnabled: boolean
  monthlyContributorsEnabled: boolean
  autoPostEnabled: boolean
  autoCommentEnabled: boolean
  autoLikeEnabled: boolean
  autoReplyEnabled: boolean
  dailySummaryHour: number
  autoPostPerDay: number
  autoCommentPerDay: number
  autoLikePerDay: number
}

export interface AiTaskLog {
  id: string
  channelId: string
  taskType: AiTaskType
  frequencyKey: string
  status: 'RUNNING' | 'SUCCESS' | 'SKIPPED' | 'FAILED'
  resultText?: string | null
  postId?: string | null
  commentId?: string | null
  errorMessage?: string | null
  startedAt?: string | null
  finishedAt?: string | null
  createdAt?: string | null
}

export interface AiTaskLogPage {
  items: AiTaskLog[]
  total: number
  page: number
  size: number
}

// ---------------------------------------------------------------------------
// 状态 / 模型 / 会话
// ---------------------------------------------------------------------------

export async function getAiStatus(): Promise<AiStatus> {
  const { data } = await http.get<AiStatus>('/ai/status')
  return data
}

export async function listAiModels(): Promise<AiModel[]> {
  const { data } = await http.get<{ items?: AiModel[] }>('/ai/models', { silent: true })
  return data.items ?? []
}

export async function listConversations(params: {
  scope?: AiScope
  channelId?: string | null
  cursor?: string | null
  limit?: number
}): Promise<{ items: AiConversation[]; nextCursor: string | null }> {
  const { data } = await http.get<{ items: AiConversation[]; nextCursor: string | null }>('/ai/conversations', {
    params: {
      scope: params.scope,
      channelId: params.channelId ?? undefined,
      cursor: params.cursor ?? undefined,
      limit: params.limit,
    },
  })
  return { items: data.items ?? [], nextCursor: data.nextCursor ?? null }
}

export async function createConversation(payload: {
  scope: AiScope
  channelId?: string | null
  title?: string
  modelConfigId?: string | null
}): Promise<AiConversation> {
  const { data } = await http.post<AiConversation>('/ai/conversations', payload)
  return data
}

export async function getConversation(id: string): Promise<AiConversation> {
  const { data } = await http.get<AiConversation>(`/ai/conversations/${encodeURIComponent(id)}`)
  return data
}

export async function renameConversation(id: string, title: string): Promise<AiConversation> {
  const { data } = await http.patch<AiConversation>(`/ai/conversations/${encodeURIComponent(id)}`, { title })
  return data
}

export async function deleteConversation(id: string): Promise<void> {
  await http.delete(`/ai/conversations/${encodeURIComponent(id)}`)
}

export async function listMessages(
  conversationId: string,
  params: { cursor?: string | null; limit?: number } = {},
): Promise<{ items: AiMessage[]; nextCursor: string | null }> {
  const { data } = await http.get<{ items: AiMessage[]; nextCursor: string | null }>(
    `/ai/conversations/${encodeURIComponent(conversationId)}/messages`,
    { params: { cursor: params.cursor ?? undefined, limit: params.limit } },
  )
  return { items: data.items ?? [], nextCursor: data.nextCursor ?? null }
}

// ---------------------------------------------------------------------------
// 流式对话
// ---------------------------------------------------------------------------

/**
 * 发送提问（流式）。regenerate=true 时复用最后一条提问重新生成。
 */
export function streamChat(
  conversationId: string,
  payload: SendMessagePayload,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  return postStream(
    `/ai/conversations/${encodeURIComponent(conversationId)}/messages/stream`,
    {
      content: payload.content ?? '',
      imageUrls: payload.imageUrls ?? [],
      modelConfigId: payload.modelConfigId ?? null,
      deepThinking: payload.deepThinking === true,
      regenerate: payload.regenerate === true,
    },
    handlers,
    signal,
  )
}

/** 重新生成最后一条回答（流式）。 */
export function regenerateChat(
  conversationId: string,
  payload: { modelConfigId?: string | null; deepThinking?: boolean },
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  return postStream(
    `/ai/conversations/${encodeURIComponent(conversationId)}/regenerate`,
    { modelConfigId: payload.modelConfigId ?? null, deepThinking: payload.deepThinking === true },
    handlers,
    signal,
  )
}

/** 编辑历史提问并重发（流式，后端截断该消息之后的全部消息）。 */
export function editAndResend(
  messageId: string,
  payload: { content: string; modelConfigId?: string | null; deepThinking?: boolean },
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  return postStream(
    `/ai/messages/${encodeURIComponent(messageId)}/edit-stream`,
    {
      content: payload.content,
      modelConfigId: payload.modelConfigId ?? null,
      deepThinking: payload.deepThinking === true,
    },
    handlers,
    signal,
  )
}

/** 停止生成（置位服务端取消标记，已生成内容落库为 ABORTED）。 */
export async function stopMessage(messageId: string): Promise<void> {
  await http.post(`/ai/messages/${encodeURIComponent(messageId)}/stop`, {}, { silent: true })
}

// ---------------------------------------------------------------------------
// 频道助手
// ---------------------------------------------------------------------------

export async function getAssistantInfo(channelId: string): Promise<AssistantInfo> {
  const { data } = await http.get<AssistantInfo>(`/channels/${encodeURIComponent(channelId)}/ai/assistant`, {
    silent: true,
  })
  return data
}

export async function getAssistantSettings(channelId: string): Promise<AssistantSettings> {
  const { data } = await http.get<AssistantSettings>(`/channels/${encodeURIComponent(channelId)}/ai/settings`)
  return data
}

export async function saveAssistantSettings(
  channelId: string,
  payload: AssistantSettingsPayload,
): Promise<AssistantSettings> {
  const { data } = await http.put<AssistantSettings>(
    `/channels/${encodeURIComponent(channelId)}/ai/settings`,
    payload,
  )
  return data
}

export async function ensureAssistantAccount(channelId: string): Promise<AssistantInfo> {
  const { data } = await http.post<AssistantInfo>(
    `/channels/${encodeURIComponent(channelId)}/ai/assistant-account`,
  )
  return data
}

export async function listAssistantTaskLogs(
  channelId: string,
  params: { taskType?: AiTaskType | null; page?: number; size?: number } = {},
): Promise<AiTaskLogPage> {
  const { data } = await http.get<AiTaskLogPage>(`/channels/${encodeURIComponent(channelId)}/ai/task-logs`, {
    params: { taskType: params.taskType ?? undefined, page: params.page, size: params.size },
  })
  return data
}

export async function runAssistantTask(channelId: string, taskType: AiTaskType): Promise<AiTaskLog> {
  // 同步等待模型生成，小模型也可能要 1–2 分钟，默认 30s 超时会误报失败
  const { data } = await http.post<AiTaskLog>(`/channels/${encodeURIComponent(channelId)}/ai/tasks/run`, { taskType }, {
    timeout: 240000,
  })
  return data
}

// ---------------------------------------------------------------------------
// SSE 底层实现
// ---------------------------------------------------------------------------

interface StreamEvent {
  event: string
  data: string
}

async function postStream(
  path: string,
  payload: unknown,
  handlers: StreamHandlers,
  signal?: AbortSignal,
  retried = false,
): Promise<void> {
  const token = getAccessToken()
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(payload),
    signal,
  })

  if (response.status === 401 && !retried) {
    if (await refreshTokenOnce()) {
      return postStream(path, payload, handlers, signal, true)
    }
  }
  const contentType = response.headers.get('content-type') ?? ''
  if (!response.ok || !response.body || !contentType.includes('text/event-stream')) {
    throw await toStreamError(response)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let completed = false
  try {
    // eslint-disable-next-line no-constant-condition
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
      let separator = buffer.indexOf('\n\n')
      while (separator >= 0) {
        const block = buffer.slice(0, separator)
        buffer = buffer.slice(separator + 2)
        const parsed = parseEventBlock(block)
        if (parsed) {
          completed = true
          dispatchEvent(parsed, handlers)
        }
        separator = buffer.indexOf('\n\n')
      }
    }
    const tail = parseEventBlock(buffer)
    if (tail) {
      completed = true
      dispatchEvent(tail, handlers)
    }
  } catch (error) {
    if (isAbortError(error) || signal?.aborted) {
      return
    }
    throw error
  } finally {
    if (!completed) {
      reader.cancel().catch(() => undefined)
    }
  }
}

function parseEventBlock(block: string): StreamEvent | null {
  if (!block || !block.trim()) return null
  let event = 'message'
  const dataLines: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trim())
    }
  }
  if (dataLines.length === 0) return null
  return { event, data: dataLines.join('\n') }
}

function dispatchEvent(event: StreamEvent, handlers: StreamHandlers): void {
  let payload: unknown
  try {
    payload = JSON.parse(event.data)
  } catch {
    return
  }
  switch (event.event) {
    case 'meta':
      handlers.onMeta?.(payload as StreamMeta)
      break
    case 'delta':
      handlers.onDelta?.((payload as { content?: string }).content ?? '')
      break
    case 'reasoning':
      handlers.onReasoning?.((payload as { content?: string }).content ?? '')
      break
    case 'done':
      handlers.onDone?.(payload as StreamDone)
      break
    case 'error':
      handlers.onError?.(payload as StreamError)
      break
    default:
      break
  }
}

async function toStreamError(response: Response): Promise<AppError> {
  let code = `HTTP_${response.status}`
  let message = response.status === 401 ? t('ai.sessionExpired') : t('ai.requestFailed')
  try {
    const data = (await response.json()) as { error?: { code?: string; message?: string } }
    if (data?.error) {
      code = data.error.code ?? code
      message = data.error.message ?? message
    }
  } catch {
    // 非 JSON 响应（代理错误页等）：保留默认文案
  }
  return new AppError({ code, message, status: response.status })
}

let refreshPromise: Promise<boolean> | null = null

async function refreshTokenOnce(): Promise<boolean> {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      try {
        const [{ useAuthStore }, { pinia }] = await Promise.all([import('@/stores/auth'), import('@/stores')])
        const token = await useAuthStore(pinia).refresh()
        return Boolean(token)
      } catch {
        return false
      } finally {
        refreshPromise = null
      }
    })()
  }
  return refreshPromise
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}
