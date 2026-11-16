import { http } from '@/api/client'

/**
 * AI 用户代理后台接口（docs/12 §7）。
 *
 * <p>后端 Agent P0 与前端并行开发，字段以 docs/12 §3 的表结构为准；
 * 这里对 snake_case / camelCase、嵌套 profile 都做容错，接口未就绪时页面走空态。
 */
export type AgentStatus = 'ACTIVE' | 'PAUSED' | 'DISABLED'
export type AgentTone = 'casual' | 'enthusiastic' | 'dry'
export type AgentRunStatus = 'SUCCESS' | 'GUARDED' | 'FAILED' | 'SKIPPED'
export type AgentOutputType = 'POST' | 'COMMENT' | 'LIKE' | 'NONE'

export interface AgentProfile {
  interests: string[]
  tone: AgentTone
  /** 活跃时段，每段 [startHour, endHour)，0-24 */
  activeHours: Array<[number, number]>
  postsPerDay: number
  commentsPerDay: number
  /** 允许活动的频道 ID；空数组=全站公开频道 */
  channels: string[]
  toolsEnabled: string[]
}

export interface AgentUser {
  id: string
  userId: string
  username: string
  displayName: string
  avatarUrl?: string | null
  bio?: string | null
  status: AgentStatus
  profile: AgentProfile
  todayPosts: number
  todayComments: number
  runCount: number
  /** 后端暂未提供时保持 null，页面显示「—」而不是假 0 */
  failureRate: number | null
  lastRunStatus?: string | null
  lastRunAt?: string | null
  createdAt?: string | null
}

/** 平台级开关（GET /admin/agents/switches） */
export interface AgentSwitches {
  enabled: boolean
  dailyPostLimit: number
  dailyTokenBudget: number
}

/**
 * 「运行一次」的超时（毫秒）。
 *
 * <p>这个接口要跑完整的一轮模型调用（本地 8B 约 30–60 秒），全局 20 秒必然超时；
 * 只给这一个请求放宽到 180 秒，其他接口继续快速失败。
 */
export const RUN_NOW_TIMEOUT_MS = 180_000

/** axios 的超时在 client.ts 里被包成 AppError，原始 code 挂在 cause 上 */
export function isRequestTimeout(error: unknown): boolean {
  const code = (error as { cause?: { code?: string } } | null)?.cause?.code
  return code === 'ECONNABORTED' || code === 'ETIMEDOUT'
}

export interface RunNowResult {
  status: string
  reason?: string | null
  run: AgentRun | null
}

export interface AgentRun {
  id: string
  agentId: string
  startedAt: string
  finishedAt?: string | null
  status: AgentRunStatus
  outputType: AgentOutputType
  outputId?: string | null
  error?: string | null
  /** 跳过/拦截的具体原因（如「总开关已关闭」），后端有就展示 */
  reason?: string | null
  tokensIn?: number | null
  tokensOut?: number | null
  planJson?: unknown
  toolCallsJson?: unknown
}

export interface AgentPage {
  items: AgentUser[]
  total: number
  page: number
  size: number
}

export interface AgentRunPage {
  items: AgentRun[]
  total: number
  page: number
  size: number
}

export interface AgentProfilePayload {
  displayName?: string
  bio?: string | null
  status?: AgentStatus
  /** 头像 URL（上传后回填）；null = 清空，走首字母占位 */
  avatarUrl?: string | null
  profile: Partial<AgentProfile>
}

type Raw = Record<string, unknown>

function record(value: unknown): Raw | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Raw) : null
}

function str(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value : null
}

function num(value: unknown, fallback = 0): number {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

function strList(value: unknown): string[] {
  if (Array.isArray(value)) return value.filter((item): item is string => typeof item === 'string')
  if (typeof value === 'string' && value.trim()) {
    try {
      const parsed = JSON.parse(value)
      if (Array.isArray(parsed)) return parsed.filter((item): item is string => typeof item === 'string')
    } catch {
      return value
        .split(',')
        .map((item) => item.trim())
        .filter(Boolean)
    }
  }
  return []
}

/** active_hours 可能是 [[20,24],[0,1]] 或 "20-24,0-1" 或 [{start,end}] */
function hourRanges(value: unknown): Array<[number, number]> {
  if (typeof value === 'string' && value.trim()) {
    try {
      const parsed = JSON.parse(value)
      if (Array.isArray(parsed)) return hourRanges(parsed)
    } catch {
      return value
        .split(',')
        .map((part) => part.split('-').map((n) => Number(n)))
        .filter((pair) => pair.length === 2 && pair.every((n) => Number.isFinite(n)))
        .map((pair) => [clampHour(pair[0]), clampHour(pair[1])] as [number, number])
    }
    return []
  }
  if (!Array.isArray(value)) return []
  const ranges: Array<[number, number]> = []
  for (const item of value) {
    if (Array.isArray(item) && item.length >= 2) {
      ranges.push([clampHour(Number(item[0])), clampHour(Number(item[1]))])
    } else {
      const entry = record(item)
      if (entry) ranges.push([clampHour(num(entry.start)), clampHour(num(entry.end))])
    }
  }
  return ranges
}

function clampHour(value: number): number {
  if (!Number.isFinite(value)) return 0
  return Math.min(24, Math.max(0, Math.round(value)))
}

function statusOf(value: unknown): AgentStatus {
  const status = String(value ?? 'ACTIVE').toUpperCase()
  if (status === 'PAUSED' || status === 'DISABLED') return status
  return 'ACTIVE'
}

function toneOf(value: unknown): AgentTone {
  const tone = String(value ?? 'casual').toLowerCase()
  if (tone === 'enthusiastic' || tone === 'dry') return tone
  return 'casual'
}

export function toAgentUser(value: unknown): AgentUser | null {
  const raw = record(value)
  if (!raw) return null
  const profileRaw = record(raw.profile) ?? record(raw.agentProfile) ?? raw
  const id = str(raw.id) ?? str(raw.userId)
  if (!id) return null
  const failureRateRaw = raw.failureRate ?? raw.failure_rate
  return {
    id,
    userId: str(raw.userId) ?? id,
    username: str(raw.username) ?? '',
    displayName: str(raw.displayName) ?? str(raw.display_name) ?? str(raw.nickname) ?? str(raw.username) ?? '',
    avatarUrl: str(raw.avatarUrl) ?? str(raw.avatar_url),
    bio: str(raw.bio),
    status: statusOf(raw.status ?? profileRaw.status),
    profile: {
      interests: strList(profileRaw.interests),
      tone: toneOf(profileRaw.tone),
      activeHours: hourRanges(profileRaw.activeHours ?? profileRaw.active_hours),
      postsPerDay: num(profileRaw.postsPerDay ?? profileRaw.posts_per_day, 2),
      commentsPerDay: num(profileRaw.commentsPerDay ?? profileRaw.comments_per_day, 8),
      channels: strList(profileRaw.channels),
      toolsEnabled: strList(profileRaw.toolsEnabled ?? profileRaw.tools_enabled),
    },
    todayPosts: num(raw.todayPosts ?? raw.today_posts),
    todayComments: num(raw.todayComments ?? raw.today_comments),
    runCount: num(raw.runCount ?? raw.run_count),
    // 后端可能给 0-1 的小数，也可能给百分数；没给就保持 null（页面显示「—」）
    failureRate:
      failureRateRaw === undefined || failureRateRaw === null
        ? null
        : Number(failureRateRaw) <= 1
          ? Number(failureRateRaw)
          : Number(failureRateRaw) / 100,
    lastRunStatus: str(raw.lastRunStatus) ?? str(raw.last_run_status),
    lastRunAt: str(raw.lastRunAt) ?? str(raw.last_run_at),
    createdAt: str(raw.createdAt),
  }
}

export function toAgentRun(value: unknown): AgentRun | null {
  const raw = record(value)
  if (!raw) return null
  const id = str(raw.id)
  if (!id) return null
  const statusRaw = String(raw.status ?? 'SUCCESS').toUpperCase()
  const outputRaw = String(raw.outputType ?? raw.output_type ?? 'NONE').toUpperCase()
  return {
    id,
    agentId: str(raw.agentId) ?? str(raw.agent_id) ?? '',
    startedAt: str(raw.startedAt) ?? str(raw.started_at) ?? '',
    finishedAt: str(raw.finishedAt) ?? str(raw.finished_at),
    status: (['GUARDED', 'FAILED', 'SKIPPED'].includes(statusRaw) ? statusRaw : 'SUCCESS') as AgentRunStatus,
    outputType: (['POST', 'COMMENT', 'LIKE'].includes(outputRaw) ? outputRaw : 'NONE') as AgentOutputType,
    outputId: str(raw.outputId) ?? str(raw.output_id),
    error: str(raw.error),
    reason: str(raw.reason) ?? str(raw.skipReason) ?? str(raw.skip_reason),
    tokensIn: raw.tokensIn === undefined ? null : num(raw.tokensIn),
    tokensOut: raw.tokensOut === undefined ? null : num(raw.tokensOut),
    planJson: raw.planJson ?? raw.plan_json,
    toolCallsJson: raw.toolCallsJson ?? raw.tool_calls_json,
  }
}

function pageOf<T>(data: unknown, mapper: (item: unknown) => T | null): { items: T[]; total: number; page: number; size: number } {
  const raw = record(data) ?? {}
  const items = Array.isArray(raw.items) ? raw.items : Array.isArray(data) ? data : []
  return {
    items: items.map(mapper).filter((item): item is T => Boolean(item)),
    total: num(raw.total, items.length),
    page: num(raw.page, 1),
    size: num(raw.size, items.length),
  }
}

export async function listAdminAgents(params: {
  page?: number
  size?: number
  status?: AgentStatus | ''
} = {}): Promise<AgentPage> {
  const { data } = await http.get('/admin/agents', {
    params: {
      page: params.page ?? 1,
      size: params.size ?? 20,
      status: params.status || undefined,
    },
  })
  return pageOf(data, toAgentUser)
}

export async function getAdminAgent(agentId: string): Promise<AgentUser | null> {
  const { data } = await http.get(`/admin/agents/${encodeURIComponent(agentId)}`)
  return toAgentUser(data)
}

/**
 * 人设编辑：后端是 PUT + 扁平 SaveAgentRequest（没有嵌套 profile），
 * 这里把表单字段摊平后整体提交。
 */
export async function updateAdminAgent(agentId: string, payload: AgentProfilePayload): Promise<AgentUser | null> {
  const profile = payload.profile ?? {}
  const body: Record<string, unknown> = {
    displayName: payload.displayName,
    bio: payload.bio ?? null,
    status: payload.status,
  }
  // 头像单独判断：undefined 表示「本次不改」，null 表示「清空」
  if (payload.avatarUrl !== undefined) body.avatarUrl = payload.avatarUrl
  if (profile.interests !== undefined) body.interests = profile.interests
  if (profile.tone !== undefined) body.tone = profile.tone
  if (profile.activeHours !== undefined) body.activeHours = profile.activeHours
  if (profile.postsPerDay !== undefined) body.postsPerDay = profile.postsPerDay
  if (profile.commentsPerDay !== undefined) body.commentsPerDay = profile.commentsPerDay
  if (profile.channels !== undefined) body.channels = profile.channels
  if (profile.toolsEnabled !== undefined) body.toolsEnabled = profile.toolsEnabled
  const { data } = await http.put(`/admin/agents/${encodeURIComponent(agentId)}`, body)
  return toAgentUser(data)
}

/** 新建代理：用户名 3-32 位字母数字下划线 */
export async function createAdminAgent(payload: {
  username: string
  displayName: string
  bio?: string | null
}): Promise<AgentUser | null> {
  const { data } = await http.post('/admin/agents', payload)
  return toAgentUser(data)
}

/** 平台级开关：总开关 + 每日上限（页面页头展示） */
export async function getAgentSwitches(): Promise<AgentSwitches | null> {
  try {
    const { data } = await http.get('/admin/agents/switches')
    const raw = record(data)
    if (!raw) return null
    return {
      enabled: Boolean(raw.enabled),
      dailyPostLimit: num(raw.dailyPostLimit, 0),
      dailyTokenBudget: num(raw.dailyTokenBudget, 0),
    }
  } catch {
    return null
  }
}

export async function pauseAdminAgent(agentId: string): Promise<void> {
  await http.post(`/admin/agents/${encodeURIComponent(agentId)}/pause`)
}

export async function resumeAdminAgent(agentId: string): Promise<void> {
  await http.post(`/admin/agents/${encodeURIComponent(agentId)}/resume`)
}

/**
 * 立即运行一次：返回 { status, reason, run }，status 可能是 SKIPPED。
 *
 * <p>单独给 180 秒超时并关掉全局错误提示：超时很可能只是前端等不及了，
 * 后端仍在跑并可能已经发出内容，提示文案要由页面按「超时」而非「失败」给出。
 */
export async function runAdminAgentNow(agentId: string): Promise<RunNowResult> {
  const { data } = await http.post(
    `/admin/agents/${encodeURIComponent(agentId)}/run-now`,
    undefined,
    { timeout: RUN_NOW_TIMEOUT_MS, silent: true },
  )
  const raw = record(data) ?? {}
  return {
    status: String(raw.status ?? 'SKIPPED').toUpperCase(),
    reason: str(raw.reason),
    run: toAgentRun(raw.run),
  }
}

/** 删除代理：账号置 DISABLED，内容保留 */
export async function deleteAdminAgent(agentId: string): Promise<void> {
  await http.delete(`/admin/agents/${encodeURIComponent(agentId)}`)
}

export async function listAdminAgentRuns(
  agentId: string,
  params: { page?: number; size?: number } = {},
): Promise<AgentRunPage> {
  const { data } = await http.get(`/admin/agents/${encodeURIComponent(agentId)}/runs`, {
    params: { page: params.page ?? 1, size: params.size ?? 20 },
  })
  return pageOf(data, toAgentRun)
}
