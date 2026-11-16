import { http } from '@/api/client'
import type { AiModel, AiStatus } from '@/api/ai'

/**
 * 平台管理后台接口（docs/11）。
 *
 * <p>除 `/admin/me` 与 `/platform/*` 公开配置外，全部要求平台管理员；
 * 权限点由后端 `@RequirePlatformPermission` 校验，前端只用 `permissions` 控制菜单与按钮显隐。
 * 错误信封与 401 刷新由 client.ts 统一处理。
 */

/** 平台权限点（与后端 PlatformPermission 枚举一一对应） */
export type AdminPermission =
  | 'CONFIG_READ'
  | 'CONFIG_WRITE'
  | 'ADMIN_MANAGE'
  | 'AUDIT_READ'
  | 'USER_READ'
  | 'CONTENT_READ'
  | 'REPORT_READ'
  | 'USER_BAN'
  | 'USER_PENALTY'
  | 'CONTENT_TAKEDOWN'
  | 'REPORT_HANDLE'
  | 'AI_MANAGE'
  | 'ANNOUNCEMENT_MANAGE'
  | 'WORD_MANAGE'
  | 'STATS_READ'
  | 'AGENT_MANAGE'

export type AdminRole = 'SUPER_ADMIN' | 'OPERATOR' | 'MODERATOR' | 'VIEWER'

export interface AdminIdentity {
  admin: boolean
  userId: string
  username: string
  platformRole: string
  /** 当前账号拥有的权限点；未授权时为空数组 */
  permissions: string[]
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

/** 举报状态（V6 起统一为 PENDING / ACCEPTED / REJECTED） */
export type ReportStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED'

export interface AdminReport {
  id: string
  reporterId?: string | null
  targetType?: string | null
  targetId?: string | null
  reason?: string | null
  detail?: string | null
  status?: string | null
  createdAt?: string | null
  channelId?: string | null
  handlerId?: string | null
  handleAction?: string | null
  handleNote?: string | null
  handledAt?: string | null
}

export interface AdminReportPage {
  items: AdminReport[]
  total: number
  available: boolean
  dependency?: string | null
  page: number
  size: number
}

// ---------------------------------------------------------------------------
// 配置中心（元数据驱动）
// ---------------------------------------------------------------------------

export type SettingType = 'BOOL' | 'INT' | 'ENUM' | 'STRING'

export interface AdminSettingItem {
  key: string
  group: string
  label: string
  type: SettingType
  value: string
  defaultValue: string
  overridden: boolean
  publicVisible: boolean
  description?: string | null
  minValue?: number | null
  maxValue?: number | null
  allowedValues?: string[] | null
}

export interface AdminSettingGroup {
  group: string
  label: string
  items: AdminSettingItem[]
}

export interface AdminSettingHistoryItem {
  id: string
  key: string
  oldValue?: string | null
  newValue?: string | null
  actorId?: string | null
  actorName?: string | null
  ip?: string | null
  createdAt?: string | null
}

export interface AdminSettingHistoryPage {
  items: AdminSettingHistoryItem[]
  total: number
  page: number
  size: number
}

// ---------------------------------------------------------------------------
// 管理员授权
// ---------------------------------------------------------------------------

export interface AdminAccount {
  id: string
  userId: string
  username: string
  nickname?: string | null
  avatarUrl?: string | null
  role: AdminRole
  roleLabel?: string | null
  grantedBy?: string | null
  grantedAt?: string | null
  expiresAt?: string | null
  /** 由配置白名单/内置超管派生，不能撤销 */
  bootstrap: boolean
}

// ---------------------------------------------------------------------------
// 审计日志
// ---------------------------------------------------------------------------

export interface AdminAuditLog {
  id: string
  actorId?: string | null
  actorName?: string | null
  action: string
  targetType?: string | null
  targetId?: string | null
  detail?: string | null
  ip?: string | null
  userAgent?: string | null
  createdAt?: string | null
}

// ---------------------------------------------------------------------------
// 内容治理
// ---------------------------------------------------------------------------

export interface AdminContentItem {
  id: string
  targetType: string
  channelId?: string | null
  authorId?: string | null
  title?: string | null
  contentPreview?: string | null
  status: string
  createdAt?: string | null
}

export interface AdminContentPage {
  items: AdminContentItem[]
  total: number
  page: number
  size: number
}

// ---------------------------------------------------------------------------
// 处罚
// ---------------------------------------------------------------------------

export type PenaltyType = 'BAN' | 'MUTE' | 'WARN'

export interface AdminPenalty {
  id: string
  userId: string
  type: PenaltyType
  scope?: string | null
  channelId?: string | null
  reason?: string | null
  reportId?: string | null
  operatorId?: string | null
  startAt?: string | null
  endAt?: string | null
  status: string
  revokedBy?: string | null
  revokedAt?: string | null
  createdAt?: string | null
}

// ---------------------------------------------------------------------------
// 公告
// ---------------------------------------------------------------------------

export type AnnouncementStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
export type AnnouncementLevel = 'INFO' | 'WARNING' | 'CRITICAL'
export type AnnouncementPlacement = 'BANNER' | 'MODAL' | 'INBOX'

export interface AdminAnnouncement {
  id: string
  title: string
  content: string
  level: AnnouncementLevel | string
  placement: AnnouncementPlacement | string
  status: AnnouncementStatus | string
  startAt?: string | null
  endAt?: string | null
  createdBy?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface AdminAnnouncementPage {
  items: AdminAnnouncement[]
  total: number
  page: number
  size: number
}

export interface AnnouncementPayload {
  title: string
  content: string
  level: AnnouncementLevel
  placement: AnnouncementPlacement
  startAt?: string | null
  endAt?: string | null
}

// ---------------------------------------------------------------------------
// 敏感词
// ---------------------------------------------------------------------------

export type WordCategory = 'POLITICS' | 'PORN' | 'AD' | 'ABUSE' | 'CUSTOM'
export type WordAction = 'BLOCK' | 'REVIEW' | 'MASK'

export interface AdminSensitiveWord {
  id: string
  word: string
  category: WordCategory | string
  action: WordAction | string
  enabled: boolean
  createdBy?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface AdminSensitiveWordPage {
  items: AdminSensitiveWord[]
  total: number
  page: number
  size: number
}

export interface AdminWordHit {
  id: string
  word: string
  scene?: string | null
  targetId?: string | null
  userId?: string | null
  action?: string | null
  createdAt?: string | null
}

export interface AdminWordHitPage {
  items: AdminWordHit[]
  total: number
  page: number
  size: number
}

export interface WordScanHit {
  word: string
  action: string
}

// ---------------------------------------------------------------------------
// 平台数据
// ---------------------------------------------------------------------------

export interface AdminTrendPoint {
  date: string
  newUsers: number
  newPosts: number
  newComments: number
  activeUsers: number
}

export interface AdminHotKeyword {
  id: string
  keyword: string
  searchCount: number
  lastSearchedAt?: string | null
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
  /**
   * 是否允许模型调用检索工具（function calling）。
   * 后端缺省 false，因此保存时必须显式回传，否则编辑一次就会把已开启的模型关掉。
   */
  toolCallEnabled: boolean
  /** 思考参数下发方式：REASONING_EFFORT / CHAT_TEMPLATE / BOTH / NONE */
  thinkingMode?: ThinkingMode
  isDefault: boolean
  enabled: boolean
  timeoutSeconds?: number | null
  maxTokens?: number | null
}

// ---------------------------------------------------------------------------
// 身份与概览
// ---------------------------------------------------------------------------

export async function getAdminIdentity(): Promise<AdminIdentity> {
  const { data } = await http.get<AdminIdentity>('/admin/me', { silent: true })
  return data
}

export async function getAdminOverview(): Promise<AdminOverview> {
  const { data } = await http.get<AdminOverview>('/admin/overview')
  return data
}

// ---------------------------------------------------------------------------
// 平台数据与热词
// ---------------------------------------------------------------------------

export async function getAdminTrend(days = 30): Promise<AdminTrendPoint[]> {
  const { data } = await http.get<{ points: AdminTrendPoint[] }>('/admin/stats/trend', { params: { days } })
  return data.points ?? []
}

export async function listAdminHotKeywords(limit = 20): Promise<AdminHotKeyword[]> {
  const { data } = await http.get<{ items: AdminHotKeyword[] }>('/admin/hot-keywords', { params: { limit } })
  return data.items ?? []
}

export async function deleteAdminHotKeyword(id: string): Promise<void> {
  await http.delete(`/admin/hot-keywords/${encodeURIComponent(id)}`)
}

// ---------------------------------------------------------------------------
// 配置中心
// ---------------------------------------------------------------------------

export async function listAdminSettings(): Promise<AdminSettingGroup[]> {
  const { data } = await http.get<{ groups: AdminSettingGroup[] }>('/admin/config')
  return data.groups ?? []
}

export async function updateAdminSetting(key: string, value: string): Promise<AdminSettingItem> {
  const { data } = await http.put<AdminSettingItem>(`/admin/config/${encodeURIComponent(key)}`, { value })
  return data
}

export async function updateAdminSettings(items: Array<{ key: string; value: string }>): Promise<AdminSettingItem[]> {
  const { data } = await http.put<AdminSettingItem[]>('/admin/config', { items })
  return data ?? []
}

export async function resetAdminSetting(key: string): Promise<AdminSettingItem> {
  const { data } = await http.post<AdminSettingItem>(`/admin/config/${encodeURIComponent(key)}/reset`)
  return data
}

export async function listAdminSettingHistory(params: {
  key?: string
  page?: number
  size?: number
}): Promise<AdminSettingHistoryPage> {
  const { data } = await http.get<AdminSettingHistoryPage>('/admin/config/history', { params })
  return data
}

// ---------------------------------------------------------------------------
// 管理员授权
// ---------------------------------------------------------------------------

export async function listAdminAccounts(): Promise<AdminAccount[]> {
  const { data } = await http.get<{ items: AdminAccount[] }>('/admin/admins')
  return data.items ?? []
}

export async function grantAdminAccount(payload: {
  userId: string
  role: AdminRole
  expiresAt?: string | null
}): Promise<AdminAccount> {
  const { data } = await http.post<AdminAccount>('/admin/admins', payload)
  return data
}

export async function changeAdminRole(
  userId: string,
  payload: { role: AdminRole; expiresAt?: string | null; note?: string | null },
): Promise<AdminAccount> {
  const { data } = await http.put<AdminAccount>(`/admin/admins/${encodeURIComponent(userId)}`, payload)
  return data
}

export async function revokeAdminAccount(userId: string): Promise<void> {
  await http.delete(`/admin/admins/${encodeURIComponent(userId)}`)
}

// ---------------------------------------------------------------------------
// 审计日志
// ---------------------------------------------------------------------------

export async function listAdminAuditLogs(params: {
  actorId?: string
  action?: string
  targetType?: string
  targetId?: string
  from?: string
  to?: string
  page?: number
  size?: number
}): Promise<{ items: AdminAuditLog[]; total: number }> {
  const { data } = await http.get<{ items: AdminAuditLog[]; total: number }>('/admin/audit-logs', { params })
  return { items: data.items ?? [], total: data.total ?? 0 }
}

// ---------------------------------------------------------------------------
// 用户与频道
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// 举报
// ---------------------------------------------------------------------------

export async function listAdminReports(params: {
  status?: string
  targetType?: string
  keyword?: string
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
  handleAction?: 'NONE' | 'DELETE_CONTENT' | 'MUTE_AUTHOR' | 'WARN',
): Promise<{ id: string; status: string }> {
  const { data } = await http.post<{ id: string; status: string }>(
    `/admin/reports/${encodeURIComponent(reportId)}/handle`,
    { action, note: note ?? null, handleAction: handleAction ?? 'NONE' },
  )
  return data
}

// ---------------------------------------------------------------------------
// 内容治理
// ---------------------------------------------------------------------------

export async function listAdminPosts(params: {
  keyword?: string
  channelId?: string
  authorId?: string
  status?: string
  page?: number
  size?: number
}): Promise<AdminContentPage> {
  const { data } = await http.get<AdminContentPage>('/admin/posts', { params })
  return data
}

export async function takedownAdminPost(id: string, reason: string): Promise<{ id: string; status: string }> {
  const { data } = await http.post<{ id: string; status: string }>(
    `/admin/posts/${encodeURIComponent(id)}/takedown`,
    { reason },
  )
  return data
}

export async function restoreAdminPost(id: string): Promise<{ id: string; status: string }> {
  const { data } = await http.post<{ id: string; status: string }>(`/admin/posts/${encodeURIComponent(id)}/restore`)
  return data
}

export async function listAdminComments(params: {
  keyword?: string
  channelId?: string
  authorId?: string
  status?: string
  page?: number
  size?: number
}): Promise<AdminContentPage> {
  const { data } = await http.get<AdminContentPage>('/admin/comments', { params })
  return data
}

export async function takedownAdminComment(id: string, reason: string): Promise<{ id: string; status: string }> {
  const { data } = await http.post<{ id: string; status: string }>(
    `/admin/comments/${encodeURIComponent(id)}/takedown`,
    { reason },
  )
  return data
}

export async function restoreAdminComment(id: string): Promise<{ id: string; status: string }> {
  const { data } = await http.post<{ id: string; status: string }>(`/admin/comments/${encodeURIComponent(id)}/restore`)
  return data
}

// ---------------------------------------------------------------------------
// 处罚
// ---------------------------------------------------------------------------

export async function listUserPenalties(userId: string): Promise<AdminPenalty[]> {
  const { data } = await http.get<{ items: AdminPenalty[] }>(`/admin/users/${encodeURIComponent(userId)}/penalties`)
  return data.items ?? []
}

export async function createUserPenalty(
  userId: string,
  payload: { type: PenaltyType; reason: string; durationHours?: number | null; reportId?: string | null },
): Promise<AdminPenalty> {
  const { data } = await http.post<AdminPenalty>(`/admin/users/${encodeURIComponent(userId)}/penalties`, payload)
  return data
}

export async function revokePenalty(penaltyId: string): Promise<AdminPenalty> {
  const { data } = await http.post<AdminPenalty>(`/admin/penalties/${encodeURIComponent(penaltyId)}/revoke`)
  return data
}

// ---------------------------------------------------------------------------
// 公告
// ---------------------------------------------------------------------------

export async function listAdminAnnouncements(params: {
  status?: string
  page?: number
  size?: number
}): Promise<AdminAnnouncementPage> {
  const { data } = await http.get<AdminAnnouncementPage>('/admin/announcements', { params })
  return data
}

export async function createAdminAnnouncement(payload: AnnouncementPayload): Promise<AdminAnnouncement> {
  const { data } = await http.post<AdminAnnouncement>('/admin/announcements', payload)
  return data
}

export async function updateAdminAnnouncement(
  id: string,
  payload: AnnouncementPayload,
): Promise<AdminAnnouncement> {
  const { data } = await http.put<AdminAnnouncement>(`/admin/announcements/${encodeURIComponent(id)}`, payload)
  return data
}

export async function publishAdminAnnouncement(id: string): Promise<AdminAnnouncement> {
  const { data } = await http.post<AdminAnnouncement>(`/admin/announcements/${encodeURIComponent(id)}/publish`)
  return data
}

export async function archiveAdminAnnouncement(id: string): Promise<AdminAnnouncement> {
  const { data } = await http.post<AdminAnnouncement>(`/admin/announcements/${encodeURIComponent(id)}/archive`)
  return data
}

export async function deleteAdminAnnouncement(id: string): Promise<void> {
  await http.delete(`/admin/announcements/${encodeURIComponent(id)}`)
}

/** 前台读取生效中的公告（公开接口，游客也能看到）。 */
export async function listActiveAnnouncements(): Promise<AdminAnnouncement[]> {
  const { data } = await http.get<{ items: AdminAnnouncement[] }>('/platform/announcements', { silent: true })
  return data.items ?? []
}

// ---------------------------------------------------------------------------
// 敏感词库
// ---------------------------------------------------------------------------

export async function listAdminSensitiveWords(params: {
  keyword?: string
  page?: number
  size?: number
}): Promise<AdminSensitiveWordPage> {
  const { data } = await http.get<AdminSensitiveWordPage>('/admin/sensitive-words', { params })
  return data
}

export async function createAdminSensitiveWord(payload: {
  word: string
  category: WordCategory
  action: WordAction
  enabled: boolean
}): Promise<AdminSensitiveWord> {
  const { data } = await http.post<AdminSensitiveWord>('/admin/sensitive-words', payload)
  return data
}

export async function updateAdminSensitiveWord(
  id: string,
  payload: { word: string; category: WordCategory; action: WordAction; enabled: boolean },
): Promise<AdminSensitiveWord> {
  const { data } = await http.put<AdminSensitiveWord>(`/admin/sensitive-words/${encodeURIComponent(id)}`, payload)
  return data
}

export async function deleteAdminSensitiveWord(id: string): Promise<void> {
  await http.delete(`/admin/sensitive-words/${encodeURIComponent(id)}`)
}

export async function importAdminSensitiveWords(payload: {
  words: string
  category: WordCategory
  action: WordAction
}): Promise<{ created: number; skipped: number }> {
  const { data } = await http.post<{ created: number; skipped: number }>('/admin/sensitive-words/import', payload)
  return data
}

export async function scanAdminSensitiveWords(text: string): Promise<WordScanHit[]> {
  const { data } = await http.post<{ hits: WordScanHit[] }>('/admin/sensitive-words/scan', { text })
  return data.hits ?? []
}

export async function listAdminWordHits(params: {
  scene?: string
  word?: string
  page?: number
  size?: number
}): Promise<AdminWordHitPage> {
  const { data } = await http.get<AdminWordHitPage>('/admin/sensitive-words/hits', { params })
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
