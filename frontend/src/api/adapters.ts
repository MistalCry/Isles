import { t } from '@/i18n'
import type {
  AuditLogItem,
  Board,
  Channel,
  ChannelMemberItem,
  ChannelOverview,
  ChannelRecommendation,
  ChannelRole,
  ChannelRoleItem,
  ChannelSummary,
  Comment,
  ContentFormat,
  DmConversation,
  DmMessage,
  DmPostShare,
  FriendItem,
  FriendRequestItem,
  HotKeyword,
  InviteLinkItem,
  JoinRequestItem,
  LikeResult,
  ManageStats,
  MediaItem,
  NotificationItem,
  Post,
  ReportItem,
  StatDistributionItem,
  TrendPoint,
  UserCard,
  UserSummary,
} from '@/types/models'

/**
 * 后端 DTO → 前端模型适配层（Anti-Corruption Layer）。
 *
 * 后端响应使用扁平字段（authorId/authorNickname/channelName…）与布尔名（liked/pinned/joined），
 * 前端组件面向嵌套模型（author/channel/board、isLiked/isPinned/isJoined）。所有差异在这里收敛，
 * 页面组件不再感知后端字段命名。契约见 docs/05 §3.4。
 */

type Raw = Record<string, unknown>

function str(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null
}

function num(value: unknown): number {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function bool(value: unknown): boolean {
  return value === true
}

function record(value: unknown): Raw | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Raw) : null
}

function toChannelRole(value: unknown): ChannelRole | null {
  const raw = record(value)
  if (!raw) return null
  return {
    code: str(raw.code) ?? '',
    name: str(raw.name) ?? '',
    color: str(raw.color),
  }
}

/** 作者信息：兼容嵌套 author 与后端扁平 authorXxx 字段。 */
function toAuthor(raw: Raw): UserSummary {
  const nested = record(raw.author)
  if (nested) return toUserSummary(nested)
  const nickname = str(raw.authorNickname)
  return {
    id: str(raw.authorId) ?? '',
    username: str(raw.authorUsername) ?? nickname ?? '',
    nickname,
    avatarUrl: str(raw.authorAvatarUrl),
    channelRole: toChannelRole(raw.authorChannelRole),
    roles: null,
  }
}

export function toMediaItem(value: unknown): MediaItem {
  const raw = record(value) ?? {}
  // 后端上传响应字段是 mediaType（MediaUploadResponse），列表/详情里是 type；
  // 只读 type 会让 type 变 undefined，前端把图片当视频渲染、提交时后端直接 400。
  const rawType = (str(raw.type) ?? str(raw.mediaType) ?? '').toUpperCase()
  const type = rawType === 'VIDEO' ? 'VIDEO' : 'IMAGE'
  return {
    id: str(raw.id) ?? undefined,
    type,
    url: str(raw.url) ?? '',
    thumbUrl: str(raw.thumbUrl),
    width: typeof raw.width === 'number' ? raw.width : null,
    height: typeof raw.height === 'number' ? raw.height : null,
    durationMs: typeof raw.durationMs === 'number' ? raw.durationMs : null,
  }
}

function normalizeContentFormat(value: unknown): ContentFormat {
  const raw = (str(value) ?? 'PLAIN').toUpperCase()
  // 历史数据里出现过 TEXT/MARKDOWN，统一归一到后端枚举（PLAIN/HTML）
  return raw === 'HTML' ? 'HTML' : 'PLAIN'
}

export function toUserSummary(value: unknown): UserSummary {
  const raw = record(value) ?? {}
  return {
    id: str(raw.id) ?? '',
    username: str(raw.username) ?? str(raw.nickname) ?? '',
    nickname: str(raw.nickname),
    avatarUrl: str(raw.avatarUrl),
    signature: str(raw.signature),
    gender: (str(raw.gender) as UserSummary['gender']) ?? undefined,
    region: str(raw.region),
    channelRole: toChannelRole(raw.channelRole),
    roles: Array.isArray(raw.roles)
      ? raw.roles.map((item) => toChannelRole(item)).filter((role): role is ChannelRole => role !== null)
      : null,
  }
}

export function toUserCard(value: unknown): UserCard {
  const raw = record(value) ?? {}
  return {
    ...toUserSummary(raw),
    joinedAt: str(raw.joinedAt),
    channelRole: toChannelRole(raw.channelRole) ?? (str(raw.roleName) ? { code: '', name: str(raw.roleName)! } : null),
    createdAt: str(raw.createdAt) ?? undefined,
  }
}

export function toBoard(value: unknown): Board {
  const raw = record(value) ?? {}
  return {
    id: str(raw.id) ?? '',
    channelId: str(raw.channelId) ?? '',
    name: str(raw.name) ?? '',
    description: str(raw.description),
    sortOrder: typeof raw.sortOrder === 'number' ? raw.sortOrder : num(raw.sort),
    sort: num(raw.sort),
    postCount: num(raw.postCount),
  }
}

export function toChannelSummary(value: unknown): ChannelSummary {
  const raw = record(value) ?? {}
  const joined = raw.isJoined !== undefined ? bool(raw.isJoined) : bool(raw.joined)
  const permissions = Array.isArray(raw.myPermissions)
    ? (raw.myPermissions as unknown[]).filter((item): item is string => typeof item === 'string')
    : null
  return {
    id: str(raw.id) ?? '',
    channelNo: (raw.channelNo as number | string | null) ?? null,
    name: str(raw.name) ?? str(raw.title) ?? '',
    avatarUrl: str(raw.avatarUrl) ?? str(raw.channelAvatarUrl),
    coverUrl: str(raw.coverUrl),
    description: str(raw.description) ?? str(raw.summary),
    type: (str(raw.type) as ChannelSummary['type']) ?? undefined,
    visibility: (str(raw.visibility) as ChannelSummary['visibility']) ?? undefined,
    ownerId: str(raw.ownerId) ?? undefined,
    memberCount: num(raw.memberCount),
    postCount: num(raw.postCount),
    isJoined: joined,
    joined,
    myRoleName: str(raw.myRoleName) ?? (record(raw.myRole) ? str((raw.myRole as Raw).name) : null),
    myPermissions: permissions,
    myMemberStatus: str(raw.myMemberStatus) ?? str(raw.memberStatus),
    muteAllUntil: str(raw.muteAllUntil),
    createdAt: str(raw.createdAt) ?? undefined,
  }
}

export function toChannel(value: unknown): Channel {
  const raw = record(value) ?? {}
  const summary = toChannelSummary(raw)
  return {
    ...summary,
    boards: Array.isArray(raw.boards) ? raw.boards.map((item) => toBoard(item)) : undefined,
    myRole: toChannelRole(raw.myRole) ?? (str(raw.myRoleName) ? { code: '', name: str(raw.myRoleName)! } : null),
  }
}

export function toPost(value: unknown): Post {
  const raw = record(value) ?? {}
  const channelId = str(raw.channelId) ?? ''
  const channelName = str(raw.channelName)
  const channel = record(raw.channel)
    ? toChannelSummary(raw.channel)
    : channelName
      ? { id: channelId, name: channelName, avatarUrl: str(raw.channelAvatarUrl) }
      : null
  const boardId = str(raw.boardId)
  const board = record(raw.board)
    ? toBoard(raw.board)
    : boardId
      ? { id: boardId, channelId, name: str(raw.boardName) ?? '' }
      : null
  const media = Array.isArray(raw.media) ? raw.media.map((item) => toMediaItem(item)) : []
  return {
    id: str(raw.id) ?? '',
    channelId,
    channel,
    board,
    author: toAuthor(raw),
    title: str(raw.title),
    content: str(raw.content) ?? str(raw.summary) ?? '',
    contentFormat: normalizeContentFormat(raw.contentFormat),
    media,
    coverUrl: str(raw.coverUrl),
    likeCount: num(raw.likeCount),
    commentCount: num(raw.commentCount),
    viewCount: num(raw.viewCount),
    shareCount: num(raw.shareCount),
    isLiked: raw.isLiked !== undefined ? bool(raw.isLiked) : bool(raw.liked),
    isPinned: raw.isPinned !== undefined ? bool(raw.isPinned) : bool(raw.pinned),
    isEssence: raw.isEssence !== undefined ? bool(raw.isEssence) : bool(raw.essence),
    createdAt: str(raw.createdAt) ?? '',
    updatedAt: str(raw.updatedAt) ?? undefined,
    topComment: record(raw.topComment) ? toComment(raw.topComment) : null,
  }
}

export function toComment(value: unknown): Comment {
  const raw = record(value) ?? {}
  const replyTo = record(raw.replyToUser)
  const replyToUser = replyTo
    ? toUserSummary(replyTo)
    : str(raw.replyToUserId)
      ? {
          id: str(raw.replyToUserId)!,
          username: str(raw.replyToNickname) ?? '',
          nickname: str(raw.replyToNickname),
          avatarUrl: null,
        }
      : null
  const parentId = str(raw.parentId)
  return {
    id: str(raw.id) ?? '',
    postId: str(raw.postId) ?? '',
    author: toAuthor(raw),
    content: str(raw.content) ?? '',
    media: Array.isArray(raw.media) ? raw.media.map((item) => toMediaItem(item)) : [],
    emoji: Array.isArray(raw.emoji) ? raw.emoji.filter((item): item is string => typeof item === 'string') : [],
    likeCount: num(raw.likeCount),
    isLiked: raw.isLiked !== undefined ? bool(raw.isLiked) : bool(raw.liked),
    parentId,
    replyToUser,
    replies: Array.isArray(raw.replies) ? raw.replies.map((item) => toComment(item)) : undefined,
    createdAt: str(raw.createdAt) ?? '',
  }
}

/** 搜索结果条目（SearchItemResponse）→ 频道摘要。 */
export function searchItemToChannel(value: unknown): ChannelSummary {
  const raw = record(value) ?? {}
  return {
    id: str(raw.id) ?? '',
    name: str(raw.title) ?? str(raw.channelName) ?? '',
    avatarUrl: str(raw.avatarUrl) ?? str(raw.channelAvatarUrl),
    description: str(raw.summary),
    memberCount: num(raw.memberCount),
    isJoined: false,
    createdAt: str(raw.createdAt) ?? undefined,
  }
}

/**
 * 搜索结果条目（SearchItemResponse，type=POST）→ 帖子卡片模型。
 *
 * <p>搜索接口的字段是逐步补齐的（coverUrl / media 可能还没有），
 * 这里一律做容错映射：拿不到就保持空，不能因为字段缺失把整页搜崩。
 */
export function searchItemToPost(value: unknown): Post {
  const raw = record(value) ?? {}
  const rawMedia = Array.isArray(raw.media) ? raw.media : []
  const media = rawMedia
    .map((item) => toMediaItem(item))
    .filter((item) => Boolean(item.url))
  const channelId = str(raw.channelId) ?? ''
  const channelName = str(raw.channelName)
  const boardId = str(raw.boardId)
  return {
    id: str(raw.id) ?? '',
    channelId,
    channel: channelName
      ? { id: channelId, name: channelName, avatarUrl: str(raw.channelAvatarUrl) }
      : null,
    board: boardId ? { id: boardId, channelId, name: str(raw.boardName) ?? '' } : null,
    author: toAuthor(raw),
    title: str(raw.title),
    content: str(raw.summary) ?? '',
    contentFormat: 'PLAIN',
    media,
    // 后端可能叫 coverUrl，也可能只给缩略图字段
    coverUrl: str(raw.coverUrl) ?? str(raw.coverThumbUrl) ?? null,
    likeCount: num(raw.likeCount),
    commentCount: num(raw.commentCount),
    viewCount: 0,
    shareCount: 0,
    isLiked: false,
    isPinned: false,
    isEssence: false,
    createdAt: str(raw.createdAt) ?? '',
  }
}

export function toLikeResult(value: unknown): LikeResult {
  const raw = record(value) ?? {}
  return {
    likeCount: num(raw.likeCount),
    isLiked: raw.isLiked !== undefined ? bool(raw.isLiked) : bool(raw.liked),
  }
}

export function toPage<T>(page: { items: unknown[]; nextCursor?: string | null; total?: number }, mapper: (item: unknown) => T) {
  return {
    items: page.items.map(mapper),
    nextCursor: page.nextCursor ?? null,
    total: page.total,
  }
}

/* -------------------------------------------------------------------------- */
/* WP1：频道管理 / 权限 / 通知                                                  */
/* -------------------------------------------------------------------------- */

function toPermissionCodes(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.filter((item): item is string => typeof item === 'string')
  }
  if (typeof value === 'string') {
    try {
      const parsed: unknown = JSON.parse(value)
      return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === 'string') : []
    } catch {
      return []
    }
  }
  return []
}

export function toChannelRoleItem(value: unknown): ChannelRoleItem {
  const raw = record(value) ?? {}
  return {
    id: str(raw.id) ?? '',
    channelId: str(raw.channelId) ?? '',
    name: str(raw.name) ?? '',
    description: str(raw.description),
    permissions: toPermissionCodes(raw.permissions),
    scopeBoardId: str(raw.scopeBoardId),
    isSystem: bool(raw.isSystem),
    sort: num(raw.sort),
    memberCount: raw.memberCount === undefined ? undefined : num(raw.memberCount),
  }
}

export function toChannelMember(value: unknown): ChannelMemberItem {
  const raw = record(value) ?? {}
  const nestedUser = record(raw.user)
  const source = nestedUser ?? raw
  return {
    id: str(raw.id) ?? undefined,
    userId: str(raw.userId) ?? str(source.id) ?? '',
    username: str(source.username) ?? str(raw.username) ?? '',
    nickname: str(source.nickname) ?? str(raw.nickname),
    avatarUrl: str(source.avatarUrl) ?? str(raw.avatarUrl),
    roleId: str(raw.roleId),
    roleName: str(raw.roleName) ?? str(raw.role),
    status: str(raw.status),
    mutedUntil: str(raw.mutedUntil),
    joinedAt: str(raw.joinedAt),
    signature: str(source.signature),
  }
}

export function toInviteLink(value: unknown): InviteLinkItem {
  const raw = record(value) ?? {}
  return {
    id: str(raw.id) ?? '',
    channelId: str(raw.channelId) ?? undefined,
    token: str(raw.token),
    url: str(raw.url),
    requiresApproval: bool(raw.requiresApproval),
    expiresAt: str(raw.expiresAt),
    maxUses: raw.maxUses === null || raw.maxUses === undefined ? null : num(raw.maxUses),
    usedCount: num(raw.usedCount),
    revokedAt: str(raw.revokedAt),
    createdBy: str(raw.createdBy),
    createdAt: str(raw.createdAt),
  }
}

export function toJoinRequest(value: unknown): JoinRequestItem {
  const raw = record(value) ?? {}
  const nestedUser = record(raw.user) ?? record(raw.applicant)
  const source = nestedUser ?? raw
  return {
    id: str(raw.id) ?? '',
    channelId: str(raw.channelId) ?? undefined,
    userId: str(raw.userId) ?? str(source.id) ?? '',
    username: str(source.username) ?? '',
    nickname: str(source.nickname) ?? str(raw.nickname),
    avatarUrl: str(source.avatarUrl) ?? str(raw.avatarUrl),
    inviteId: str(raw.inviteId),
    status: str(raw.status) ?? 'PENDING',
    createdAt: str(raw.createdAt),
    reviewedAt: str(raw.reviewedAt),
  }
}

export function toNotification(value: unknown): NotificationItem {
  const raw = record(value) ?? {}
  return {
    id: str(raw.id) ?? '',
    type: str(raw.type) ?? 'SYSTEM',
    channelId: str(raw.channelId),
    postId: str(raw.postId),
    commentId: str(raw.commentId),
    actorId: str(raw.actorId),
    actorNickname: str(raw.actorNickname) ?? str(raw.actorName),
    title: str(raw.title),
    content: str(raw.content),
    read: raw.isRead !== undefined ? bool(raw.isRead) : bool(raw.read),
    createdAt: str(raw.createdAt) ?? '',
  }
}

export function toChannelOverview(value: unknown): ChannelOverview {
  const raw = record(value) ?? {}
  // 后端 /stats/overview 把昨日指标放在 yesterday 子对象中，这里兼容扁平与嵌套两种形状
  const yesterday = record(raw.yesterday) ?? {}
  return {
    channelId: str(raw.channelId) ?? '',
    name: str(raw.name) ?? undefined,
    memberCount: num(raw.memberCount),
    postCount: num(raw.postCount),
    yesterdayNewMembers: num(raw.yesterdayNewMembers ?? raw.newMembers ?? yesterday.newMembers),
    yesterdayVisitors: num(raw.yesterdayVisitors ?? raw.visitors ?? yesterday.visitUv),
    activeRate: num(raw.activeRate ?? yesterday.activeRate),
    yesterdayNewPosts: num(raw.yesterdayNewPosts ?? raw.newPosts ?? yesterday.posts),
    totalViews: num(raw.totalViews ?? yesterday.postViews),
    totalInteractions: num(raw.totalInteractions ?? yesterday.interactions),
  }
}

function toTrendPoint(value: unknown): TrendPoint {
  const raw = record(value) ?? {}
  return {
    date: str(raw.date) ?? str(raw.day) ?? str(raw.statDate) ?? '',
    members: num(raw.members ?? raw.memberCount),
    visits: num(raw.visits ?? raw.uv ?? raw.visitUv),
    posts: num(raw.posts ?? raw.postCount),
    interactions: num(raw.interactions),
    activeRate: num(raw.activeRate),
  }
}

function toDistribution(value: unknown): StatDistributionItem {
  const raw = record(value) ?? {}
  return {
    name: str(raw.name) ?? str(raw.label) ?? str(raw.boardName) ?? str(raw.roleName) ?? t('common.other'),
    value: num(raw.value ?? raw.count ?? raw.postCount),
  }
}

function toTopPost(value: unknown): Post {
  const raw = record(value) ?? {}
  return toPost({
    ...raw,
    id: raw.id ?? raw.postId,
    content: raw.content ?? raw.contentPreview ?? '',
  })
}

export function toManageStats(value: unknown): ManageStats {
  const raw = record(value) ?? {}
  const trend = Array.isArray(raw.trend) ? raw.trend.map(toTrendPoint) : []
  const boardSource = Array.isArray(raw.boardDistribution)
    ? raw.boardDistribution
    : Array.isArray(record(raw.content)?.boardDistribution)
      ? (record(raw.content)!.boardDistribution as unknown[])
      : []
  const roleSource = Array.isArray(raw.roleDistribution)
    ? raw.roleDistribution
    : Array.isArray(record(raw.members)?.roleDistribution)
      ? (record(raw.members)!.roleDistribution as unknown[])
      : []
  const topPostsSource = Array.isArray(raw.topPosts)
    ? raw.topPosts
    : Array.isArray(record(raw.content)?.topPosts)
      ? (record(raw.content)!.topPosts as unknown[])
      : []
  return {
    overview: record(raw.overview) ? toChannelOverview(raw.overview) : null,
    trend,
    boardDistribution: boardSource.map(toDistribution),
    roleDistribution: roleSource.map(toDistribution),
    topPosts: topPostsSource.map(toTopPost),
  }
}

export function toAuditLog(value: unknown): AuditLogItem {
  const raw = record(value) ?? {}
  return {
    id: str(raw.id) ?? '',
    channelId: str(raw.channelId) ?? undefined,
    actorId: str(raw.actorId) ?? str(raw.operatorId),
    actorNickname: str(raw.actorNickname) ?? str(raw.operatorNickname),
    action: str(raw.action) ?? str(raw.actionType) ?? '',
    targetType: str(raw.targetType),
    targetId: str(raw.targetId),
    detail: str(raw.detail) ?? str(raw.description),
    ip: str(raw.ip),
    createdAt: str(raw.createdAt) ?? '',
  }
}

export function toReport(value: unknown): ReportItem {
  const raw = record(value) ?? {}
  return {
    id: str(raw.id) ?? '',
    channelId: str(raw.channelId),
    targetType: str(raw.targetType) ?? '',
    targetId: str(raw.targetId) ?? '',
    targetTitle: str(raw.targetTitle),
    reason: str(raw.reason) ?? '',
    description: str(raw.description),
    status: str(raw.status) ?? 'PENDING',
    reporterId: str(raw.reporterId),
    reporterNickname: str(raw.reporterNickname) ?? str(raw.reporterName),
    createdAt: str(raw.createdAt) ?? '',
    handledAt: str(raw.handledAt),
    handleNote: str(raw.handleNote),
  }
}

/* -------------------------------------------------------------------------- */
/* WP2：好友 / 私信                                                             */
/* -------------------------------------------------------------------------- */

export function toFriend(value: unknown): FriendItem {
  const raw = record(value) ?? {}
  const nested = record(raw.user) ?? record(raw.friend)
  const source = nested ?? raw
  return {
    id: str(raw.id) ?? undefined,
    userId: str(raw.userId) ?? str(raw.friendId) ?? str(source.id) ?? '',
    username: str(source.username) ?? '',
    nickname: str(source.nickname) ?? str(raw.nickname),
    avatarUrl: str(source.avatarUrl) ?? str(raw.avatarUrl),
    signature: str(source.signature) ?? str(raw.signature),
    online: bool(raw.online),
    since: str(raw.since) ?? str(raw.createdAt) ?? str(raw.friendSince),
  }
}

export function toFriendRequest(value: unknown): FriendRequestItem {
  const raw = record(value) ?? {}
  const fromUser = record(raw.fromUser) ?? record(raw.requester)
  const toUser = record(raw.toUser) ?? record(raw.target)
  return {
    id: str(raw.id) ?? '',
    direction: str(raw.direction) ?? undefined,
    fromUser: fromUser ? toUserSummary(fromUser) : null,
    toUser: toUser ? toUserSummary(toUser) : null,
    status: str(raw.status) ?? 'PENDING',
    message: str(raw.message),
    createdAt: str(raw.createdAt) ?? '',
    handledAt: str(raw.handledAt),
  }
}

export function toDmPostShare(value: unknown): DmPostShare {
  const raw = record(value) ?? {}
  return {
    postId: str(raw.postId) ?? str(raw.id) ?? '',
    title: str(raw.title),
    summary: str(raw.summary) ?? str(raw.content),
    channelId: str(raw.channelId),
    channelName: str(raw.channelName),
    coverUrl: str(raw.coverUrl),
    authorNickname: str(raw.authorNickname),
  }
}

export function toDmMessage(value: unknown): DmMessage {
  const raw = record(value) ?? {}
  const sender = record(raw.sender) ?? null
  const post = record(raw.post) ?? record(raw.postShare) ?? (str(raw.postId) ? raw : null)
  const type = (str(raw.type) ?? 'TEXT').toUpperCase() as DmMessage['type']
  return {
    id: str(raw.id) ?? '',
    conversationId: str(raw.conversationId) ?? '',
    senderId: str(raw.senderId) ?? str(raw.fromUserId) ?? '',
    sender: sender ? toUserSummary(sender) : null,
    type: type === 'POST_SHARE' ? 'POST' : type,
    content: str(raw.content) ?? str(raw.text),
    media: Array.isArray(raw.media) ? raw.media.map(toMediaItem) : [],
    post: post ? toDmPostShare(post) : null,
    read: raw.isRead !== undefined ? bool(raw.isRead) : bool(raw.read),
    createdAt: str(raw.createdAt) ?? '',
    clientMessageId: str(raw.clientMessageId),
  }
}

export function toDmConversation(value: unknown): DmConversation {
  const raw = record(value) ?? {}
  const peerRaw = record(raw.peer) ?? record(raw.participant) ?? record(raw.targetUser) ?? record(raw.user)
  const peer = peerRaw ? toUserSummary(peerRaw) : toUserSummary(raw)
  return {
    id: str(raw.id) ?? '',
    peer,
    lastMessage: record(raw.lastMessage) ? toDmMessage(raw.lastMessage) : null,
    unreadCount: num(raw.unreadCount),
    updatedAt: str(raw.updatedAt) ?? str(raw.lastMessageAt) ?? str(raw.createdAt),
    createdAt: str(raw.createdAt),
  }
}

/* -------------------------------------------------------------------------- */
/* WP3：热门 / 推荐                                                             */
/* -------------------------------------------------------------------------- */

export function toChannelRecommendation(value: unknown): ChannelRecommendation {
  const raw = record(value) ?? {}
  const channelRaw = record(raw.channel)
  const source = channelRaw ?? raw
  // 后端推荐接口是扁平结构（channelId/name/avatarUrl...），toChannelSummary 读的是 id，
  // 这里补齐 id，否则 channel.id 为空会让「加入」按钮永远处于 loading 状态。
  const channel = toChannelSummary({
    ...source,
    id: str(source.id) ?? str(raw.channelId) ?? str(source.channelId) ?? '',
  })
  return {
    channel,
    reason: str(raw.reason) ?? str(raw.recommendReason) ?? t('recommend.defaultReason'),
    score: num(raw.score),
  }
}

export function toHotKeyword(value: unknown): HotKeyword {
  if (typeof value === 'string') {
    return { keyword: value }
  }
  const raw = record(value) ?? {}
  return {
    keyword: str(raw.keyword) ?? str(raw.word) ?? '',
    count: raw.count === undefined ? undefined : num(raw.count),
  }
}

