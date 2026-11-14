import { computed, ref, type Ref } from 'vue'
import { AppError, resolveErrorMessage } from '@/api/client'
import type { PageResult } from '@/types/models'

export interface CursorListState<T> {
  items: Ref<T[]>
  loading: Ref<boolean>
  error: Ref<string | null>
  errorCode: Ref<string | null>
  errorStatus: Ref<number>
  hasMore: Ref<boolean>
  initialLoading: Ref<boolean>
  isEmpty: Ref<boolean>
  finished: Ref<boolean>
  loadMore: () => Promise<void>
  refresh: () => Promise<void>
}

/**
 * 游标分页列表（动态流/帖子流/评论流共用）。
 * - 统一加载 / 空 / 错误三态所需的响应式状态
 * - 刷新时使在途请求失效，避免旧响应覆盖新列表
 */
export function useCursorList<T>(
  fetchPage: (cursor: string | null) => Promise<PageResult<T>>,
): CursorListState<T> {
  const items = ref<T[]>([]) as Ref<T[]>
  const loading = ref(false)
  const error = ref<string | null>(null)
  const errorCode = ref<string | null>(null)
  const errorStatus = ref(0)
  const hasMore = ref(true)

  let cursor: string | null = null
  let requestSeq = 0

  const initialLoading = computed(() => loading.value && items.value.length === 0)
  const isEmpty = computed(() => !loading.value && !error.value && items.value.length === 0)
  const finished = computed(() => !hasMore.value && items.value.length > 0)

  async function load(reset: boolean): Promise<void> {
    if (loading.value && !reset) return
    if (reset) {
      requestSeq += 1
      cursor = null
      hasMore.value = true
      items.value = []
      error.value = null
      errorCode.value = null
      errorStatus.value = 0
    }
    if (!hasMore.value) return

    const seq = ++requestSeq
    loading.value = true
    try {
      const page = await fetchPage(cursor)
      if (seq !== requestSeq) return
      const batch = Array.isArray(page.items) ? page.items : []
      items.value = reset ? batch : [...items.value, ...batch]
      cursor = page.nextCursor ?? null
      hasMore.value = Boolean(cursor) && batch.length > 0
      error.value = null
      errorCode.value = null
      errorStatus.value = 0
    } catch (err) {
      if (seq !== requestSeq) return
      error.value = resolveErrorMessage(err)
      errorCode.value = err instanceof AppError ? err.code : null
      errorStatus.value = err instanceof AppError ? err.status : 0
    } finally {
      if (seq === requestSeq) {
        loading.value = false
      }
    }
  }

  return {
    items,
    loading,
    error,
    errorCode,
    errorStatus,
    hasMore,
    initialLoading,
    isEmpty,
    finished,
    loadMore: () => load(false),
    refresh: () => load(true),
  }
}
