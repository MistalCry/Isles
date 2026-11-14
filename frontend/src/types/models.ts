/**
 * 与 docs/05-API设计.md 对齐的共享类型。
 * 后端可能返回列表的多种形态（数组 / {items}），由 api/normalize.ts 统一收敛。
 */

export type Id = string

export type Gender = 'MALE' | 'FEMALE' | 'OTHER' | 'UNKNOWN'
export type ChannelType = 'PUBLIC' | 'PRIVATE'
export type ChannelVisibility = 'PUBLIC_VISIBLE' | 'MEMBERS_ONLY'
export type ContentFormat = 'TEXT' | 'HTML' | 'MARKDOWN'
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
  sortOrder?: number
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
  memberCount?: number
  postCount?: number
  isJoined?: boolean
  createdAt?: string
}

export interface Channel extends ChannelSummary {
  ownerId?: Id
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
