import type { ChannelRecommendation, FeedSort, PageResult, Post } from '@/types/models'
import { http } from './client'
import { extractItems, extractPage } from './normalize'
import { toChannelRecommendation, toPage, toPost } from './adapters'

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

/** 全站热门讨论（WP3）：公开只读，游标分页。 */
export async function getHot(query: { cursor?: string | null; limit?: number; window?: string } = {}): Promise<PageResult<Post>> {
  const { data } = await http.get('/hot', {
    params: {
      cursor: query.cursor ?? undefined,
      limit: query.limit ?? 20,
      window: query.window || undefined,
    },
  })
  return toPage(extractPage<unknown>(data), toPost)
}

/** 频道推荐：附带推荐理由（好友加入过 / 相似频道 / 近期活跃 / 浏览过相似频道）。 */
export async function getRecommendations(limit = 6, seed?: number): Promise<ChannelRecommendation[]> {
  const { data } = await http.get('/hot/recommendations', {
    params: { limit, seed },
    silent: true,
  })
  return extractItems<unknown>(data).map(toChannelRecommendation)
}
