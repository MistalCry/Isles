import type {
  ChannelSummary,
  PageResult,
  Post,
  SearchHistoryItem,
  SearchSuggestion,
  SearchType,
} from '@/types/models'
import { http } from './client'
import { extractItems, extractPage } from './normalize'
import { searchItemToChannel, searchItemToPost, toPage } from './adapters'

export interface SearchQuery {
  q: string
  sort?: string
  cursor?: string | null
  limit?: number
}

export async function searchChannels(query: SearchQuery): Promise<PageResult<ChannelSummary>> {
  const { data } = await http.get('/search', {
    params: {
      q: query.q,
      type: 'channel',
      sort: query.sort,
      cursor: query.cursor ?? undefined,
      limit: query.limit,
    },
  })
  return toPage(extractPage<unknown>(data), searchItemToChannel)
}

export async function searchPosts(query: SearchQuery): Promise<PageResult<Post>> {
  const { data } = await http.get('/search', {
    params: {
      q: query.q,
      type: 'post',
      sort: query.sort,
      cursor: query.cursor ?? undefined,
      limit: query.limit,
    },
  })
  return toPage(extractPage<unknown>(data), searchItemToPost)
}

export interface SearchAllResult {
  channels: ChannelSummary[]
  posts: Post[]
}

/**
 * 综合搜索结果：契约未明确 type=all 的混合结构，改为并行请求两类资源，
 * 结果稳定且便于分别渲染空态（详见 docs/05 §2.5）。
 */
export async function searchAll(q: string): Promise<SearchAllResult> {
  const [channelsPage, postsPage] = await Promise.all([
    searchChannels({ q, limit: 6 }),
    searchPosts({ q, limit: 10 }),
  ])
  return { channels: channelsPage.items, posts: postsPage.items }
}

export async function getSearchSuggestion(q: string): Promise<SearchSuggestion> {
  try {
    const { data } = await http.get('/search/suggest', { params: { q }, silent: true })
    const record = (data ?? {}) as Record<string, unknown>
    return {
      channels: extractItems(record.channels ?? []).map(searchItemToChannel),
      keywords: Array.isArray(record.keywords) ? (record.keywords as string[]) : [],
    }
  } catch {
    // 联想为 P1 增强能力，失败时静默降级为普通搜索
    return { channels: [], keywords: [] }
  }
}

export type { SearchType }

export async function getSearchHistory(): Promise<SearchHistoryItem[]> {
  const { data } = await http.get<SearchHistoryItem[] | { items: SearchHistoryItem[] }>('/search/history', {
    silent: true,
  })
  return extractItems<SearchHistoryItem>(data)
}

export async function deleteSearchHistory(id: string): Promise<void> {
  await http.delete(`/search/history/${encodeURIComponent(id)}`, { silent: true })
}

export async function clearSearchHistory(): Promise<void> {
  await http.delete('/search/history', { silent: true })
}
