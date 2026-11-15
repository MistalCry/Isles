import type {
  AuditLogItem,
  Board,
  Channel,
  ChannelMemberItem,
  ChannelOverview,
  ChannelRoleItem,
  InviteLinkItem,
  JoinRequestItem,
  ManageStats,
  Post,
  ReportHandleAction,
  ReportItem,
} from '@/types/models'
import { http } from './client'
import { extractItems, extractPage } from './normalize'
import {
  toAuditLog,
  toBoard,
  toChannel,
  toChannelMember,
  toChannelOverview,
  toChannelRoleItem,
  toInviteLink,
  toJoinRequest,
  toManageStats,
  toPost,
  toReport,
} from './adapters'

/**
 * 频道管理与内容治理接口（与后端 `/api/v1` 实际契约一致）。
 *
 * 约定：
 * - 频道资料：PATCH /channels/{id}；解散：POST /channels/{id}/dissolve
 * - 成员/身份组/邀请/审批/禁言：/channels/{id}/members|roles|invites|join-requests|mute-all
 * - 内容管理：GET /channels/{id}/posts/manage、/channels/{id}/posts/{postId}/pin|essence、批量 /posts/batch
 * - 数据中心：/channels/{id}/stats/{overview|trends|content|members}
 * - 日志/举报：/channels/{id}/audit-logs、/channels/{id}/reports
 * - 管理端列表统一 page/size 偏移分页，返回 items + total
 */

export interface OffsetListResult<T> {
  items: T[]
  total?: number
}

export interface UpdateChannelPayload {
  name?: string
  avatarUrl?: string | null
  coverUrl?: string | null
  description?: string | null
  /** 加入方式：公开（自由加入）/ 私密（邀请加入） */
  type?: 'PUBLIC' | 'PRIVATE'
  visibility?: 'PUBLIC_VISIBLE' | 'MEMBERS_ONLY'
}

export interface OffsetQuery {
  page?: number
  size?: number
}

export interface MemberQuery extends OffsetQuery {
  keyword?: string
}

export interface ContentQuery extends OffsetQuery {
  keyword?: string
  authorId?: string
  boardId?: string
  status?: string
  from?: string
  to?: string
}

export interface CreateRolePayload {
  name: string
  description?: string
  permissions: string[]
  scopeBoardId?: string | null
}

export interface UpdateRolePayload {
  name?: string
  description?: string
  permissions?: string[]
  scopeBoardId?: string | null
}

export interface CreateBoardPayload {
  name: string
  description?: string
  sort?: number
}

export interface CreateInvitePayload {
  requiresApproval?: boolean
  expiresInSeconds?: number
  maxUses?: number
}

export interface LogQuery extends OffsetQuery {
  actorId?: string
  action?: string
  from?: string
  to?: string
}

export interface ReportQuery extends OffsetQuery {
  status?: string
}

export interface HandleReportPayload {
  action: ReportHandleAction
  note?: string
}

export interface BatchPostPayload {
  action: 'DELETE' | 'PIN' | 'UNPIN' | 'ESSENCE' | 'UNESSENCE'
  postIds: string[]
}

function toOffsetList<T>(data: unknown, mapper: (item: unknown) => T): OffsetListResult<T> {
  const page = extractPage<unknown>(data)
  return { items: page.items.map(mapper), total: page.total }
}

function channelPath(channelId: string, suffix = ''): string {
  return `/channels/${encodeURIComponent(channelId)}${suffix}`
}

/* -------------------------------- 频道资料 -------------------------------- */

export async function updateChannel(channelId: string, payload: UpdateChannelPayload): Promise<Channel> {
  const { data } = await http.patch(channelPath(channelId), payload)
  return toChannel(data)
}

export async function dissolveChannel(channelId: string): Promise<void> {
  await http.post(channelPath(channelId, '/dissolve'))
}

export async function getManageOverview(channelId: string): Promise<ChannelOverview> {
  // 后端数据中心接口：实时数字 + 昨日指标（/stats/overview）
  const { data } = await http.get(`${channelPath(channelId, '/stats/overview')}`)
  return toChannelOverview(data)
}

/**
 * 数据中心：后端按维度拆分为 overview/trends/content/members 四个接口，
 * 这里并发拉取后合并为页面所需的单一结构，避免页面感知接口拆分。
 */
export async function getManageStats(channelId: string, days = 7): Promise<ManageStats> {
  const [overview, trend, content, members] = await Promise.all([
    http.get(`${channelPath(channelId, '/stats/overview')}`).then((res) => res.data),
    http.get(`${channelPath(channelId, '/stats/trends')}`, { params: { days } }).then((res) => res.data),
    http.get(`${channelPath(channelId, '/stats/content')}`).then((res) => res.data),
    http.get(`${channelPath(channelId, '/stats/members')}`, { params: { days } }).then((res) => res.data),
  ])
  return toManageStats({ overview, trend, content, members })
}

export async function getManageLogs(channelId: string, query: LogQuery = {}): Promise<OffsetListResult<AuditLogItem>> {
  const { data } = await http.get(`${channelPath(channelId, '/audit-logs')}`, {
    params: {
      actorId: query.actorId || undefined,
      action: query.action || undefined,
      from: query.from || undefined,
      to: query.to || undefined,
      page: query.page ?? 1,
      size: query.size ?? 20,
    },
  })
  return toOffsetList(data, toAuditLog)
}

/* -------------------------------- 成员 / 身份组 ------------------------------- */

export async function getMembers(
  channelId: string,
  query: MemberQuery = {},
): Promise<OffsetListResult<ChannelMemberItem>> {
  const { data } = await http.get(channelPath(channelId, '/members'), {
    params: {
      keyword: query.keyword || undefined,
      page: query.page ?? 1,
      size: query.size ?? 20,
    },
  })
  return toOffsetList(data, toChannelMember)
}

export async function getRoles(channelId: string): Promise<ChannelRoleItem[]> {
  const { data } = await http.get(channelPath(channelId, '/roles'))
  return extractItems<unknown>(data).map(toChannelRoleItem)
}

export async function createRole(channelId: string, payload: CreateRolePayload): Promise<ChannelRoleItem> {
  const { data } = await http.post(channelPath(channelId, '/roles'), payload)
  return toChannelRoleItem(data)
}

export async function updateRole(
  channelId: string,
  roleId: string,
  payload: UpdateRolePayload,
): Promise<ChannelRoleItem> {
  const { data } = await http.patch(channelPath(channelId, `/roles/${encodeURIComponent(roleId)}`), payload)
  return toChannelRoleItem(data)
}

export async function deleteRole(channelId: string, roleId: string): Promise<void> {
  await http.delete(channelPath(channelId, `/roles/${encodeURIComponent(roleId)}`))
}

export async function assignMemberRole(channelId: string, userId: string, roleId: string): Promise<void> {
  await http.post(channelPath(channelId, `/members/${encodeURIComponent(userId)}/role`), { roleId })
}

export async function muteMember(channelId: string, userId: string, durationSeconds: number): Promise<void> {
  await http.post(channelPath(channelId, `/members/${encodeURIComponent(userId)}/mute`), { durationSeconds })
}

export async function unmuteMember(channelId: string, userId: string): Promise<void> {
  await http.delete(channelPath(channelId, `/members/${encodeURIComponent(userId)}/mute`))
}

export async function removeMember(channelId: string, userId: string): Promise<void> {
  await http.delete(channelPath(channelId, `/members/${encodeURIComponent(userId)}`))
}

/** 全员禁言；durationSeconds 为 0 表示解除。 */
export async function setMuteAll(channelId: string, durationSeconds: number): Promise<void> {
  if (durationSeconds <= 0) {
    await http.delete(channelPath(channelId, '/mute-all'))
    return
  }
  await http.post(channelPath(channelId, '/mute-all'), { durationSeconds })
}

/* --------------------------------- 邀请 / 审批 -------------------------------- */

export async function getInvites(channelId: string): Promise<InviteLinkItem[]> {
  const { data } = await http.get(channelPath(channelId, '/invites'))
  return extractItems<unknown>(data).map(toInviteLink)
}

export async function createInvite(channelId: string, payload: CreateInvitePayload): Promise<InviteLinkItem> {
  const { data } = await http.post(channelPath(channelId, '/invites'), payload)
  return toInviteLink(data)
}

export async function revokeInvite(channelId: string, inviteId: string): Promise<void> {
  await http.delete(channelPath(channelId, `/invites/${encodeURIComponent(inviteId)}`))
}

export async function getJoinRequests(
  channelId: string,
  status = 'PENDING',
): Promise<JoinRequestItem[]> {
  const { data } = await http.get(channelPath(channelId, '/join-requests'), { params: { status } })
  return extractItems<unknown>(data).map(toJoinRequest)
}

export async function approveJoinRequest(channelId: string, requestId: string): Promise<void> {
  await http.post(channelPath(channelId, `/join-requests/${encodeURIComponent(requestId)}/approve`))
}

export async function rejectJoinRequest(channelId: string, requestId: string): Promise<void> {
  await http.post(channelPath(channelId, `/join-requests/${encodeURIComponent(requestId)}/reject`))
}

/* --------------------------------- 板块管理 --------------------------------- */

export async function createBoard(channelId: string, payload: CreateBoardPayload): Promise<Board> {
  const { data } = await http.post(channelPath(channelId, '/boards'), payload)
  return toBoard(data)
}

export async function updateBoard(
  channelId: string,
  boardId: string,
  payload: Partial<CreateBoardPayload>,
): Promise<Board> {
  const { data } = await http.patch(channelPath(channelId, `/boards/${encodeURIComponent(boardId)}`), payload)
  return toBoard(data)
}

export async function deleteBoard(channelId: string, boardId: string): Promise<void> {
  await http.delete(channelPath(channelId, `/boards/${encodeURIComponent(boardId)}`))
}

/* --------------------------------- 内容管理 --------------------------------- */

export async function getContentPosts(
  channelId: string,
  query: ContentQuery = {},
): Promise<OffsetListResult<Post>> {
  const { data } = await http.get(channelPath(channelId, '/posts/manage'), {
    params: {
      keyword: query.keyword || undefined,
      authorId: query.authorId || undefined,
      boardId: query.boardId || undefined,
      status: query.status || undefined,
      from: query.from || undefined,
      to: query.to || undefined,
      page: query.page ?? 1,
      size: query.size ?? 20,
    },
  })
  return toOffsetList(data, toPost)
}

export async function pinPost(channelId: string, postId: string, pinned: boolean): Promise<void> {
  if (pinned) {
    await http.post(channelPath(channelId, `/posts/${encodeURIComponent(postId)}/pin`))
  } else {
    await http.delete(channelPath(channelId, `/posts/${encodeURIComponent(postId)}/pin`))
  }
}

export async function essencePost(channelId: string, postId: string, essence: boolean): Promise<void> {
  if (essence) {
    await http.post(channelPath(channelId, `/posts/${encodeURIComponent(postId)}/essence`))
  } else {
    await http.delete(channelPath(channelId, `/posts/${encodeURIComponent(postId)}/essence`))
  }
}

export async function deletePost(channelId: string, postId: string): Promise<void> {
  await http.delete(channelPath(channelId, `/posts/${encodeURIComponent(postId)}`))
}

export async function batchPostAction(channelId: string, payload: BatchPostPayload): Promise<void> {
  await http.post(channelPath(channelId, '/posts/batch'), payload)
}

export async function deleteComment(commentId: string): Promise<void> {
  await http.delete(`/comments/${encodeURIComponent(commentId)}`)
}

/* -------------------------------- 举报 / 治理 -------------------------------- */

export async function submitReport(payload: {
  targetType: 'POST' | 'COMMENT'
  targetId: string
  reason: string
  description?: string
}): Promise<void> {
  await http.post('/reports', payload)
}

export async function getChannelReports(
  channelId: string,
  query: ReportQuery = {},
): Promise<OffsetListResult<ReportItem>> {
  const { data } = await http.get(channelPath(channelId, '/reports'), {
    params: {
      status: query.status || undefined,
      page: query.page ?? 1,
      size: query.size ?? 20,
    },
  })
  return toOffsetList(data, toReport)
}

export async function handleReport(
  channelId: string,
  reportId: string,
  payload: HandleReportPayload,
): Promise<void> {
  // 前端动作语义 → 后端状态机：APPROVE/REJECT 映射 status，DELETE_CONTENT 额外携带处置动作
  const status = payload.action === 'REJECT' ? 'REJECTED' : 'ACCEPTED'
  const action = payload.action === 'DELETE_CONTENT' ? 'DELETE_CONTENT' : 'NONE'
  await http.post(channelPath(channelId, `/reports/${encodeURIComponent(reportId)}/handle`), {
    status,
    action,
    note: payload.note,
  })
}
