import type { PageResult } from '@/types/models'

/**
 * 后端列表可能返回数组或 `{ items }` / 领域命名字段（channels、posts…），
 * 前端统一收敛，避免每个页面各写一套兼容逻辑。
 */
export function extractItems<T>(data: unknown): T[] {
  if (Array.isArray(data)) return data as T[]
  if (data && typeof data === 'object') {
    const record = data as Record<string, unknown>
    for (const key of ['items', 'channels', 'posts', 'comments', 'records', 'list']) {
      const value = record[key]
      if (Array.isArray(value)) return value as T[]
    }
  }
  return []
}

export function extractPage<T>(data: unknown): PageResult<T> {
  const items = extractItems<T>(data)
  if (data && typeof data === 'object' && !Array.isArray(data)) {
    const record = data as Record<string, unknown>
    const nextCursor = typeof record.nextCursor === 'string' ? record.nextCursor : null
    const total = typeof record.total === 'number' ? record.total : undefined
    return { items, nextCursor, total }
  }
  return { items, nextCursor: null }
}
