import { http } from './client'
import { extractPage } from './normalize'
import { toPage, toPost } from './adapters'
import type { FeedSort, PageResult, Post } from '@/types/models'

export interface FeedQuery {
  sort?: FeedSort
  cursor?: string | null
  limit?: number
}

/** 频道动态：登录用户已加入频道的帖子流（docs/05 §2.4）。 */
export async function getFeed(query: FeedQuery = {}): Promise<PageResult<Post>> {
  const { data } = await http.get('/feed', {
    params: {
      sort: query.sort ?? 'latest',
      cursor: query.cursor ?? undefined,
      limit: query.limit,
    },
  })
  return toPage(extractPage<unknown>(data), toPost)
}
