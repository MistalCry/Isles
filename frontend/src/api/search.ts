import type {
  ChannelSummary,
  HotKeyword,
  PageResult,
  Post,
  SearchHistoryItem,
  SearchSuggestion,
  SearchType,
} from '@/types/models'
import { http } from './client'
import { extractItems, extractPage } from './normalize'
import { searchItemToChannel, searchItemToPost, toHotKeyword, toPage } from './adapters'

export interface SearchQuery {
  q: string
  sort?: string
  cursor?: string | null
  limit?: number
  scope?: 'global' | 'channel'
  channelId?: string
}

function searchParams(query: SearchQuery, type: 'channel' | 'post') {
  return {
    q: query.q,
    type,
    sort: query.sort,
    scope: query.scope === 'channel' ? 'channel' : undefined,
    channelId: query.scope === 'channel' ? query.channelId : undefined,
    cursor: query.cursor ?? undefined,
    limit: query.limit,
  }
}

export async function searchChannels(query: SearchQuery): Promise<PageResult<ChannelSummary>> {
  const { data } = await http.get('/search', { params: searchParams(query, 'channel') })
  return toPage(extractPage<unknown>(data), searchItemToChannel)
}

export async function searchPosts(query: SearchQuery): Promise<PageResult<Post>> {
  const { data } = await http.get('/search', { params: searchParams(query, 'post') })
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
export async function searchAll(query: string, options: { scope?: 'global' | 'channel'; channelId?: string } = {}): Promise<SearchAllResult> {
  const [channelsPage, postsPage] = await Promise.all([
    searchChannels({ q: query, limit: 6, scope: options.scope, channelId: options.channelId }),
    searchPosts({ q: query, limit: 10, scope: options.scope, channelId: options.channelId }),
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
    // 联想失败时静默降级为普通搜索
    return { channels: [], keywords: [] }
  }
}

/** 搜索热词（WP3）：后端未提供时返回空数组，页面展示“暂无热词”。 */
export async function getHotKeywords(limit = 10): Promise<HotKeyword[]> {
  try {
    const { data } = await http.get('/search/hot-keywords', { params: { limit }, silent: true })
    const record = (data ?? {}) as Record<string, unknown>
    const source = Array.isArray(data) ? data : (record.keywords ?? record.items ?? [])
    return extractItems<unknown>(source).map(toHotKeyword).filter((item) => item.keyword)
  } catch {
    return []
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
