import { http } from '@/api/client'
import type { AiModel, AiStatus } from '@/api/ai'

/**
 * 平台管理后台接口（docs/03 §4.16、docs/05 待细化项）。
 * 除 /admin/me 外全部要求平台管理员；错误信封与 401 刷新由 client.ts 统一处理。
 */

export interface AdminIdentity {
  admin: boolean
  userId: string
  username: string
  platformRole: string
}

export interface AdminOverview {
  userCount: number
  channelCount: number
  postCount: number
  commentCount: number
  todayNewUsers: number
  todayNewPosts: number
  todayActiveUsers: number
  aiConversationCount: number
  aiMessageCount: number
  pendingReportCount: number
  reportTableReady: boolean
}

export interface AdminUser {
  id: string
  username: string
  nickname?: string | null
  avatarUrl?: string | null
  signature?: string | null
  status: string
  platformRole: string
  createdAt?: string | null
}

export interface AdminUserPage {
  items: AdminUser[]
  total: number
  page: number
  size: number
}

export interface AdminChannel {
  id: string
  channelNo?: number | null
  name: string
  avatarUrl?: string | null
  ownerId?: string | null
  memberCount: number
  postCount: number
  type?: string | null
  visibility?: string | null
  status: string
  createdAt?: string | null
}

export interface AdminChannelPage {
  items: AdminChannel[]
  total: number
  page: number
  size: number
}

export interface AdminReport {
  id: string
  reporterId?: string | null
  targetType?: string | null
  targetId?: string | null
  reason?: string | null
  detail?: string | null
  status?: string | null
  createdAt?: string | null
}

export interface AdminReportPage {
  items: AdminReport[]
  total: number
  available: boolean
  dependency?: string | null
  page: number
  size: number
}

export interface ConnectionTestResult {
  success: boolean
  latencyMs: number
  models: string[]
  message: string
}

export type ThinkingMode = 'REASONING_EFFORT' | 'CHAT_TEMPLATE' | 'BOTH' | 'NONE'

export interface ModelConfigPayload {
  name: string
  baseUrl: string
  apiKey?: string | null
  model: string
  visionEnabled: boolean
  reasoningEnabled: boolean
  /** 思考参数下发方式：REASONING_EFFORT / CHAT_TEMPLATE / BOTH / NONE */
  thinkingMode?: ThinkingMode
  isDefault: boolean
  enabled: boolean
  timeoutSeconds?: number | null
  maxTokens?: number | null
}

export async function getAdminIdentity(): Promise<AdminIdentity> {
  const { data } = await http.get<AdminIdentity>('/admin/me', { silent: true })
  return data
}

export async function getAdminOverview(): Promise<AdminOverview> {
  const { data } = await http.get<AdminOverview>('/admin/overview')
  return data
}

export async function listAdminUsers(params: {
  keyword?: string
  status?: string
  page?: number
  size?: number
}): Promise<AdminUserPage> {
  const { data } = await http.get<AdminUserPage>('/admin/users', { params })
  return data
}

export async function banAdminUser(userId: string): Promise<AdminUser> {
  const { data } = await http.post<AdminUser>(`/admin/users/${encodeURIComponent(userId)}/ban`)
  return data
}

export async function unbanAdminUser(userId: string): Promise<AdminUser> {
  const { data } = await http.post<AdminUser>(`/admin/users/${encodeURIComponent(userId)}/unban`)
  return data
}

export async function listAdminChannels(params: {
  keyword?: string
  status?: string
  page?: number
  size?: number
}): Promise<AdminChannelPage> {
  const { data } = await http.get<AdminChannelPage>('/admin/channels', { params })
  return data
}

export async function banAdminChannel(channelId: string): Promise<AdminChannel> {
  const { data } = await http.post<AdminChannel>(`/admin/channels/${encodeURIComponent(channelId)}/ban`)
  return data
}

export async function unbanAdminChannel(channelId: string): Promise<AdminChannel> {
  const { data } = await http.post<AdminChannel>(`/admin/channels/${encodeURIComponent(channelId)}/unban`)
  return data
}

export async function listAdminReports(params: {
  status?: string
  page?: number
  size?: number
}): Promise<AdminReportPage> {
  const { data } = await http.get<AdminReportPage>('/admin/reports', { params })
  return data
}

export async function handleAdminReport(
  reportId: string,
  action: 'DISMISS' | 'RESOLVE',
  note?: string,
): Promise<{ id: string; status: string }> {
  const { data } = await http.post<{ id: string; status: string }>(
    `/admin/reports/${encodeURIComponent(reportId)}/handle`,
    { action, note: note ?? null },
  )
  return data
}

// ---------------------------------------------------------------------------
// AI 模型配置
// ---------------------------------------------------------------------------

export async function listAdminAiModels(): Promise<AiModel[]> {
  const { data } = await http.get<{ items: AiModel[] }>('/admin/ai/models')
  return data.items ?? []
}

export async function createAdminAiModel(payload: ModelConfigPayload): Promise<AiModel> {
  const { data } = await http.post<AiModel>('/admin/ai/models', payload)
  return data
}

export async function updateAdminAiModel(id: string, payload: ModelConfigPayload): Promise<AiModel> {
  const { data } = await http.put<AiModel>(`/admin/ai/models/${encodeURIComponent(id)}`, payload)
  return data
}

export async function deleteAdminAiModel(id: string): Promise<void> {
  await http.delete(`/admin/ai/models/${encodeURIComponent(id)}`)
}

export async function testAdminAiModel(id: string): Promise<ConnectionTestResult> {
  const { data } = await http.post<ConnectionTestResult>(`/admin/ai/models/${encodeURIComponent(id)}/test`)
  return data
}

export async function testAdminAiConnection(payload: {
  baseUrl: string
  apiKey?: string | null
  model?: string | null
}): Promise<ConnectionTestResult> {
  const { data } = await http.post<ConnectionTestResult>('/admin/ai/test-connection', payload)
  return data
}

export async function listRemoteModels(id: string): Promise<string[]> {
  const { data } = await http.get<{ items: string[] }>(`/admin/ai/models/${encodeURIComponent(id)}/remote-models`)
  return data.items ?? []
}

export async function getAdminAiStatus(): Promise<AiStatus> {
  const { data } = await http.get<AiStatus>('/admin/ai/status')
  return data
}
