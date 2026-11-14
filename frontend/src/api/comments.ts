import type {
  Comment,
  CommentSort,
  CreateCommentPayload,
  LikeResult,
  PageResult,
} from '@/types/models'
import { http } from './client'
import { extractPage } from './normalize'
import { toComment, toLikeResult, toPage } from './adapters'

export interface CommentQuery {
  sort?: CommentSort
  cursor?: string | null
  limit?: number
}

export async function getComments(postId: string, query: CommentQuery = {}): Promise<PageResult<Comment>> {
  const { data } = await http.get(`/posts/${encodeURIComponent(postId)}/comments`, {
    params: {
      sort: query.sort ?? 'likes',
      cursor: query.cursor ?? undefined,
      limit: query.limit,
    },
  })
  return toPage(extractPage<unknown>(data), toComment)
}

export async function createComment(postId: string, payload: CreateCommentPayload): Promise<Comment> {
  const { data } = await http.post(`/posts/${encodeURIComponent(postId)}/comments`, payload)
  return toComment(data)
}

export async function likeComment(id: string): Promise<LikeResult> {
  const { data } = await http.post(`/comments/${encodeURIComponent(id)}/like`)
  return toLikeResult(data)
}

export async function unlikeComment(id: string): Promise<LikeResult> {
  const { data } = await http.delete(`/comments/${encodeURIComponent(id)}/like`)
  return toLikeResult(data)
}
