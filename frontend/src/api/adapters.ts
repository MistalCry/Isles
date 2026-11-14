import type {
  Board,
  Channel,
  ChannelRole,
  ChannelSummary,
  Comment,
  ContentFormat,
  LikeResult,
  MediaItem,
  Post,
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
  const type = str(raw.type)?.toUpperCase() === 'VIDEO' ? 'VIDEO' : 'IMAGE'
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
  if (raw === 'HTML') return 'HTML'
  if (raw === 'MARKDOWN') return 'MARKDOWN'
  return 'TEXT'
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
    sortOrder: typeof raw.sortOrder === 'number' ? raw.sortOrder : num(raw.sort),
  }
}

export function toChannelSummary(value: unknown): ChannelSummary {
  const raw = record(value) ?? {}
  const joined = raw.isJoined !== undefined ? bool(raw.isJoined) : bool(raw.joined)
  return {
    id: str(raw.id) ?? '',
    channelNo: (raw.channelNo as number | string | null) ?? null,
    name: str(raw.name) ?? str(raw.title) ?? '',
    avatarUrl: str(raw.avatarUrl) ?? str(raw.channelAvatarUrl),
    coverUrl: str(raw.coverUrl),
    description: str(raw.description) ?? str(raw.summary),
    type: (str(raw.type) as ChannelSummary['type']) ?? undefined,
    visibility: (str(raw.visibility) as ChannelSummary['visibility']) ?? undefined,
    memberCount: num(raw.memberCount),
    postCount: num(raw.postCount),
    isJoined: joined,
    createdAt: str(raw.createdAt) ?? undefined,
  }
}

export function toChannel(value: unknown): Channel {
  const raw = record(value) ?? {}
  const summary = toChannelSummary(raw)
  return {
    ...summary,
    ownerId: str(raw.ownerId) ?? undefined,
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

/** 搜索结果条目（SearchItemResponse，type=POST）→ 帖子卡片模型。 */
export function searchItemToPost(value: unknown): Post {
  const raw = record(value) ?? {}
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
    contentFormat: 'TEXT',
    media: [],
    coverUrl: null,
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
