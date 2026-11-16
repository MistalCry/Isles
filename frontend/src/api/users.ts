import type { PageResult, Post, UpdateProfilePayload, UserCard, UserSummary } from '@/types/models'
import { http } from './client'
import { toPage, toPost, toUserCard, toUserSummary } from './adapters'
import { extractPage } from './normalize'

export async function getUser(id: string): Promise<UserSummary> {
  const { data } = await http.get(`/users/${encodeURIComponent(id)}`)
  return toUserSummary(data)
}

/** 用户卡片：传 channelId 时返回该频道内的加入时间与身份组。 */
export async function getUserCard(id: string, channelId?: string): Promise<UserCard> {
  const { data } = await http.get(`/users/${encodeURIComponent(id)}/card`, {
    params: channelId ? { channelId } : undefined,
  })
  return toUserCard(data)
}

export async function updateMe(payload: UpdateProfilePayload): Promise<UserSummary> {
  const { data } = await http.patch('/users/me', payload)
  return toUserSummary(data)
}

/** 我的帖子：按发布时间倒序，可按频道过滤。 */
export async function getMyPosts(params: {
  page?: number
  size?: number
  channelId?: string | null
} = {}): Promise<PageResult<Post>> {
  const { data } = await http.get('/users/me/posts', {
    params: {
      page: params.page ?? 1,
      size: params.size ?? 20,
      channelId: params.channelId || undefined,
    },
  })
  return toPage(extractPage<unknown>(data), toPost)
}
