import type { LikeResult, MediaItem, Post } from '@/types/models'
import { http } from './client'
import { toLikeResult, toPost } from './adapters'

export async function getPost(id: string): Promise<Post> {
  const { data } = await http.get(`/posts/${encodeURIComponent(id)}`)
  return toPost(data)
}

/** 编辑帖子（仅作者）：返回更新后的帖子。 */
export async function updatePost(
  id: string,
  payload: {
    title?: string
    content?: string
    contentFormat?: 'PLAIN' | 'HTML'
    /** 空串=清除封面；不传=保持不变 */
    coverUrl?: string | null
    media?: MediaItem[]
  },
): Promise<Post> {
  const { data } = await http.patch(`/posts/${encodeURIComponent(id)}`, payload)
  return toPost(data)
}

export async function likePost(id: string): Promise<LikeResult> {
  const { data } = await http.post(`/posts/${encodeURIComponent(id)}/like`)
  return toLikeResult(data)
}

export async function unlikePost(id: string): Promise<LikeResult> {
  const { data } = await http.delete(`/posts/${encodeURIComponent(id)}/like`)
  return toLikeResult(data)
}

/** 分享计数为后台统计行为，失败不打断用户复制链接。 */
export async function countPostShare(id: string): Promise<void> {
  await http.post(`/posts/${encodeURIComponent(id)}/share`, undefined, { silent: true })
}
