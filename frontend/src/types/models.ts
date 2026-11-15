/**
 * 与 docs/05-API设计.md 对齐的共享类型。
 * 后端可能返回列表的多种形态（数组 / {items}），由 api/normalize.ts 统一收敛。
 */

export type Id = string

export type Gender = 'MALE' | 'FEMALE' | 'OTHER' | 'UNKNOWN'
export type ChannelType = 'PUBLIC' | 'PRIVATE'
export type ChannelVisibility = 'PUBLIC_VISIBLE' | 'MEMBERS_ONLY'
/** 与后端一致：PLAIN 纯文本 / HTML 富文本（服务端白名单净化） */
export type ContentFormat = 'PLAIN' | 'HTML'
export type MediaType = 'IMAGE' | 'VIDEO'
export type PostTab = 'latest' | 'hot' | 'essence'
export type CommentSort = 'likes' | 'latest'
export type FeedSort = 'latest' | 'hot'
export type SearchType = 'all' | 'channel' | 'post'
export type PostSort = 'relevance' | 'createdAt'

/** 频道内身份组标识（频道主 / 超级管理员 / 子板块管理员 / 普通成员）。 */
export interface ChannelRole {
  code: string
  name: string
  color?: string | null
}

export interface UserSummary {
  id: Id
  username: string
  nickname?: string | null
  avatarUrl?: string | null
  signature?: string | null
  gender?: Gender | null
  region?: string | null
  /** 当前频道下的身份组（帖子/评论上下文提供） */
  channelRole?: ChannelRole | null
  roles?: ChannelRole[] | null
}

export interface CurrentUser extends UserSummary {
  email?: string | null
  platformRole?: string
  createdAt?: string
}

export interface UserCard extends UserSummary {
  joinedAt?: string | null
  channelRole?: ChannelRole | null
  createdAt?: string
}

export interface MediaItem {
  id?: string
  type: MediaType
  url: string
  thumbUrl?: string | null
  width?: number | null
  height?: number | null
  durationMs?: number | null
}

export interface Board {
  id: Id
  channelId: Id
  name: string
  description?: string | null
  sortOrder?: number
  sort?: number
  postCount?: number
}

export interface ChannelSummary {
  id: Id
  channelNo?: number | string | null
  name: string
  avatarUrl?: string | null
  coverUrl?: string | null
  description?: string | null
  type?: ChannelType
  visibility?: ChannelVisibility
  ownerId?: Id
  memberCount?: number
  postCount?: number
  isJoined?: boolean
  /** 后端字段 joined 的原始值（适配层转写为 isJoined） */
  joined?: boolean
  /** 当前用户在该频道的身份组名称（频道主 / 超级管理员 / 子板块管理员 / 普通成员 / 自定义） */
  myRoleName?: string | null
  /** 当前用户在该频道的权限点（后端扩展字段；缺失时前端按身份组名称兜底推导） */
  myPermissions?: string[] | null
  /** 当前用户成员状态：ACTIVE / MUTED 等 */
  myMemberStatus?: string | null
  /** 全员禁言到期时间 */
  muteAllUntil?: string | null
  createdAt?: string
}

export interface Channel extends ChannelSummary {
  boards?: Board[]
  myRole?: ChannelRole | null
}

export interface Post {
  id: Id
  channelId: Id
  channel?: ChannelSummary | null
  board?: Board | null
  author: UserSummary
  title?: string | null
  content: string
  contentFormat?: ContentFormat
  media?: MediaItem[]
  coverUrl?: string | null
  likeCount: number
  commentCount: number
  viewCount?: number
  shareCount?: number
  isLiked?: boolean
  isPinned?: boolean
  isEssence?: boolean
  createdAt: string
  updatedAt?: string
  topComment?: Comment | null
}

export interface Comment {
  id: Id
  postId: Id
  author: UserSummary
  content: string
  media?: MediaItem[]
  likeCount: number
  isLiked?: boolean
  parentId?: Id | null
  replyToUser?: UserSummary | null
  replies?: Comment[]
  createdAt: string
}

export interface PageResult<T> {
  items: T[]
  nextCursor?: string | null
  total?: number
}

export interface AuthResponse {
  accessToken: string
  user?: CurrentUser | null
}

export interface LoginPayload {
  username: string
  password: string
}

export interface RegisterPayload {
  username: string
  password: string
  nickname?: string
}

export interface UpdateProfilePayload {
  nickname?: string
  signature?: string
  gender?: Gender
  region?: string
  avatarUrl?: string | null
}

export interface CreateChannelPayload {
  name: string
  avatarUrl?: string | null
  coverUrl?: string | null
  description?: string
  type: ChannelType
  visibility?: ChannelVisibility
}

export interface CreatePostPayload {
  boardId?: string | null
  title?: string
  content: string
  contentFormat: ContentFormat
  media?: MediaItem[]
  coverUrl?: string | null
}

export interface CreateCommentPayload {
  content: string
  parentId?: string | null
  replyToUserId?: string | null
  media?: MediaItem[]
}

export interface LikeResult {
  likeCount?: number
  isLiked?: boolean
}

export interface SearchParams {
  q: string
  type?: SearchType
  sort?: string
  cursor?: string | null
  limit?: number
  scope?: 'global' | 'channel'
  channelId?: string
}

export interface SearchSuggestion {
  channels: ChannelSummary[]
  keywords: string[]
}

export interface SearchHistoryItem {
  id: string
  keyword: string
  createdAt?: string
}

export interface HotKeyword {
  keyword: string
  count?: number
}

/* -------------------------------------------------------------------------- */
/* WP1：频道管理 / 权限 / 通知                                                  */
/* -------------------------------------------------------------------------- */

/** 身份组管理条目（管理端；权限点数组与后端 roles.permissions 对齐）。 */
export interface ChannelRoleItem {
  id: Id
  channelId: Id
  name: string
  description?: string | null
  permissions: string[]
  scopeBoardId?: string | null
  isSystem: boolean
  sort?: number
  memberCount?: number
}

export interface PermissionPoint {
  code: string
  name: string
  group: string
}

/** 频道成员（管理端；后端返回扁平字段）。 */
export interface ChannelMemberItem {
  id?: string
  userId: Id
  username: string
  nickname?: string | null
  avatarUrl?: string | null
  roleId?: string | null
  roleName?: string | null
  status?: string | null
  mutedUntil?: string | null
  joinedAt?: string | null
  signature?: string | null
}

export interface InviteLinkItem {
  id: Id
  channelId?: Id
  token?: string | null
  url?: string | null
  requiresApproval: boolean
  expiresAt?: string | null
  maxUses?: number | null
  usedCount?: number
  revokedAt?: string | null
  /** 后端状态：ACTIVE / REVOKED / EXPIRED / EXHAUSTED */
  status?: string | null
  revoked?: boolean
  createdBy?: string | null
  createdAt?: string | null
}

export interface JoinRequestItem {
  id: Id
  channelId?: Id
  userId: Id
  username: string
  nickname?: string | null
  avatarUrl?: string | null
  inviteId?: string | null
  status: string
  createdAt?: string | null
  reviewedAt?: string | null
}

export interface NotificationItem {
  id: Id
  type: string
  channelId?: string | null
  postId?: string | null
  commentId?: string | null
  actorId?: string | null
  actorNickname?: string | null
  title?: string | null
  content?: string | null
  read: boolean
  createdAt: string
}

export interface NotificationQuery {
  channelId?: string
  type?: string
  unreadOnly?: boolean
  cursor?: string | null
  limit?: number
}

export interface ChannelOverview {
  channelId: Id
  name?: string
  memberCount: number
  postCount: number
  yesterdayNewMembers?: number
  yesterdayVisitors?: number
  activeRate?: number
  yesterdayNewPosts?: number
  totalViews?: number
  totalInteractions?: number
}

export interface TrendPoint {
  date: string
  members?: number
  visits?: number
  posts?: number
  interactions?: number
  activeRate?: number
}

export interface StatDistributionItem {
  name: string
  value: number
}

export interface ManageStats {
  overview?: ChannelOverview | null
  trend: TrendPoint[]
  boardDistribution: StatDistributionItem[]
  roleDistribution: StatDistributionItem[]
  topPosts: Post[]
}

export interface AuditLogItem {
  id: Id
  channelId?: Id
  actorId?: string | null
  actorNickname?: string | null
  action: string
  targetType?: string | null
  targetId?: string | null
  detail?: string | null
  ip?: string | null
  createdAt: string
}

export type ReportTargetType = 'POST' | 'COMMENT'
export type ReportStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'HANDLED' | string
export type ReportHandleAction = 'APPROVE' | 'REJECT' | 'DELETE_CONTENT'

export interface ReportItem {
  id: Id
  channelId?: string | null
  targetType: string
  targetId: string
  targetTitle?: string | null
  reason: string
  description?: string | null
  status: ReportStatus
  reporterId?: string | null
  reporterNickname?: string | null
  createdAt: string
  handledAt?: string | null
  handleNote?: string | null
}

/* -------------------------------------------------------------------------- */
/* WP2：好友 / 私信                                                             */
/* -------------------------------------------------------------------------- */

export interface FriendItem {
  id?: string
  userId: Id
  username: string
  nickname?: string | null
  avatarUrl?: string | null
  signature?: string | null
  online?: boolean
  since?: string | null
}

export interface FriendRequestItem {
  id: Id
  direction?: 'INCOMING' | 'OUTGOING' | string
  fromUser?: UserSummary | null
  toUser?: UserSummary | null
  status: string
  message?: string | null
  createdAt: string
  handledAt?: string | null
}

export type DmMessageType = 'TEXT' | 'IMAGE' | 'POST' | 'POST_SHARE' | 'SYSTEM'

export interface DmPostShare {
  postId: string
  title?: string | null
  summary?: string | null
  channelId?: string | null
  channelName?: string | null
  coverUrl?: string | null
  authorNickname?: string | null
}

export interface DmMessage {
  id: Id
  conversationId: Id
  senderId: string
  sender?: UserSummary | null
  type: DmMessageType
  content?: string | null
  media?: MediaItem[]
  post?: DmPostShare | null
  read?: boolean
  createdAt: string
  clientMessageId?: string | null
}

export interface DmConversation {
  id: Id
  peer: UserSummary
  lastMessage?: DmMessage | null
  unreadCount: number
  updatedAt?: string | null
  createdAt?: string | null
}

/* -------------------------------------------------------------------------- */
/* WP3：热门 / 推荐                                                             */
/* -------------------------------------------------------------------------- */

export interface ChannelRecommendation {
  channel: ChannelSummary
  reason: string
  score?: number
}

export interface ApiErrorDetail {
  field?: string
  message?: string
}

export interface ApiErrorEnvelope {
  error: {
    code: string
    message: string
    details?: ApiErrorDetail[] | Record<string, unknown> | unknown[]
    requestId?: string
  }
}
