import type { UpdateProfilePayload, UserCard, UserSummary } from '@/types/models'
import { http } from './client'
import { toUserCard, toUserSummary } from './adapters'

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
