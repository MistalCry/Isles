import type { FriendItem, FriendRequestItem } from '@/types/models'
import { http } from './client'
import { extractItems } from './normalize'
import { toFriend, toFriendRequest } from './adapters'

/**
 * 好友接口（WP2 契约假设，docs/03 FR-USER-05 / FR-DM-04）：
 * GET /friends、GET /friends/requests、POST /friends/requests、POST /friends/requests/{id}/accept|reject、
 * DELETE /friends/{userId}、POST/DELETE /users/{userId}/block、GET /users/blocked。
 */
export async function getFriends(): Promise<FriendItem[]> {
  const { data } = await http.get('/friends')
  return extractItems<unknown>(data).map(toFriend)
}

export async function getBlockedUsers(): Promise<FriendItem[]> {
  const { data } = await http.get('/users/blocked')
  return extractItems<unknown>(data).map(toFriend)
}

export async function getFriendRequests(direction: 'incoming' | 'outgoing' = 'incoming'): Promise<FriendRequestItem[]> {
  const { data } = await http.get('/friends/requests', { params: { direction } })
  return extractItems<unknown>(data).map(toFriendRequest)
}

export async function sendFriendRequest(userId: string, message?: string): Promise<FriendRequestItem | null> {
  const { data } = await http.post('/friends/requests', { userId, message })
  return data ? toFriendRequest(data) : null
}

export async function acceptFriendRequest(requestId: string): Promise<void> {
  await http.post(`/friends/requests/${encodeURIComponent(requestId)}/accept`)
}

export async function rejectFriendRequest(requestId: string): Promise<void> {
  await http.post(`/friends/requests/${encodeURIComponent(requestId)}/reject`)
}

export async function removeFriend(userId: string): Promise<void> {
  await http.delete(`/friends/${encodeURIComponent(userId)}`)
}

export async function blockUser(userId: string): Promise<void> {
  await http.post(`/users/${encodeURIComponent(userId)}/block`)
}

export async function unblockUser(userId: string): Promise<void> {
  await http.delete(`/users/${encodeURIComponent(userId)}/block`)
}
