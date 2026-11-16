import type {
  Board,
  Channel,
  ChannelSummary,
  CreateChannelPayload,
  CreatePostPayload,
  PageResult,
  Post,
  PostTab,
} from '@/types/models'
import { http } from './client'
import { extractItems, extractPage } from './normalize'
import { toBoard, toChannel, toChannelSummary, toPage, toPost } from './adapters'

export async function createChannel(payload: CreateChannelPayload): Promise<Channel> {
  const { data } = await http.post('/channels', payload)
  return toChannel(data)
}

export async function getChannel(id: string): Promise<Channel> {
  const { data } = await http.get(`/channels/${encodeURIComponent(id)}`)
  return toChannel(data)
}

export async function getMyChannels(): Promise<ChannelSummary[]> {
  const { data } = await http.get('/channels/mine', { silent: true })
  return extractItems(data).map(toChannelSummary)
}

export async function joinChannel(id: string): Promise<Channel> {
  const { data } = await http.post(`/channels/${encodeURIComponent(id)}/join`)
  return toChannel(data)
}

export interface InviteAcceptResult {
  status: string
  channelId?: string
  joinRequestId?: string
}

/** 使用邀请链接：返回 JOINED 或 PENDING（需审批）。 */
export async function acceptInvite(token: string): Promise<InviteAcceptResult> {
  const { data } = await http.post<InviteAcceptResult>(`/invites/${encodeURIComponent(token)}/accept`)
  return data
}

export async function leaveChannel(id: string): Promise<void> {
  await http.post(`/channels/${encodeURIComponent(id)}/leave`)
}

export async function getBoards(channelId: string): Promise<Board[]> {
  const { data } = await http.get(`/channels/${encodeURIComponent(channelId)}/boards`, { silent: true })
  return extractItems(data).map(toBoard)
}

export interface ChannelPostQuery {
  tab?: PostTab
  boardId?: string | null
  cursor?: string | null
  limit?: number
}

export async function getChannelPosts(channelId: string, query: ChannelPostQuery = {}): Promise<PageResult<Post>> {
  const { data } = await http.get(`/channels/${encodeURIComponent(channelId)}/posts`, {
    params: {
      tab: query.tab ?? 'latest',
      boardId: query.boardId ?? undefined,
      cursor: query.cursor ?? undefined,
      limit: query.limit,
    },
  })
  return toPage(extractPage<unknown>(data), toPost)
}

/** 频道置顶帖：后端已把置顶帖从 latest/essence 列表排除，置顶区单独取。 */
export async function getPinnedPosts(channelId: string, limit = 20): Promise<Post[]> {
  const { data } = await http.get(`/channels/${encodeURIComponent(channelId)}/posts/pinned`, {
    params: { limit },
  })
  return (extractPage<unknown>(data).items ?? []).map(toPost)
}

export async function createChannelPost(channelId: string, payload: CreatePostPayload): Promise<Post> {
  const { data } = await http.post(`/channels/${encodeURIComponent(channelId)}/posts`, payload)
  return toPost(data)
}

/** @提及候选：频道内活跃成员（含频道助手），用于编辑器的 @ 联想。 */
export async function getMentionCandidates(
  channelId: string,
  keyword?: string,
): Promise<Array<{ id: string; username?: string | null; nickname?: string | null; avatarUrl?: string | null }>> {
  const { data } = await http.get(`/channels/${encodeURIComponent(channelId)}/mention-candidates`, {
    params: { q: keyword || undefined },
    silent: true,
  })
  return Array.isArray(data) ? data : []
}
