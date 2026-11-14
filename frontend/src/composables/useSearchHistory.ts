import { computed, ref } from 'vue'
import { searchApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { MAX_SEARCH_HISTORY } from '@/constants/app'
import type { SearchHistoryItem } from '@/types/models'

const STORAGE_KEY = 'isles:search-history'

function createId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `local_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`
}

interface LocalHistoryItem extends SearchHistoryItem {
  createdAt: string
}

function readLocalHistory(): LocalHistoryItem[] {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter(
        (item): item is LocalHistoryItem =>
          Boolean(item) &&
          typeof item === 'object' &&
          typeof (item as LocalHistoryItem).id === 'string' &&
          typeof (item as LocalHistoryItem).keyword === 'string',
      )
      .slice(0, MAX_SEARCH_HISTORY)
  } catch {
    return []
  }
}

function writeLocalHistory(items: LocalHistoryItem[]): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(items.slice(0, MAX_SEARCH_HISTORY)))
  } catch {
    // 隐私模式等场景 localStorage 不可用：搜索历史仅保留在内存
  }
}

/**
 * 搜索历史：登录用户优先读取服务端（docs/05 §2.5），
 * 游客或接口不可用时回退到 localStorage，保证 M1 演示可用且不伪造数据。
 */
export function useSearchHistory() {
  const auth = useAuthStore()
  const items = ref<SearchHistoryItem[]>([])
  const loading = ref(false)
  const usingLocalFallback = ref(false)

  const hasHistory = computed(() => items.value.length > 0)

  function toLocalItems(source: SearchHistoryItem[]): LocalHistoryItem[] {
    return source.map((item) => ({
      id: item.id,
      keyword: item.keyword,
      createdAt: item.createdAt ?? new Date().toISOString(),
    }))
  }

  async function load(): Promise<void> {
    loading.value = true
    if (auth.isAuthenticated) {
      try {
        const remote = await searchApi.getSearchHistory()
        if (remote.length > 0) {
          items.value = remote
          usingLocalFallback.value = false
          loading.value = false
          return
        }
      } catch {
        // 接口不可用时回退本地历史
      }
    }
    items.value = readLocalHistory()
    usingLocalFallback.value = true
    loading.value = false
  }

  function record(keyword: string): void {
    const trimmed = keyword.trim()
    if (!trimmed) return
    const next: LocalHistoryItem[] = [
      { id: createId(), keyword: trimmed, createdAt: new Date().toISOString() },
      ...readLocalHistory().filter((item) => item.keyword !== trimmed),
    ].slice(0, MAX_SEARCH_HISTORY)
    writeLocalHistory(next)
    if (usingLocalFallback.value || items.value.length === 0) {
      items.value = next
    }
  }

  async function remove(id: string): Promise<void> {
    items.value = items.value.filter((item) => item.id !== id)
    writeLocalHistory(readLocalHistory().filter((item) => item.id !== id))
    if (auth.isAuthenticated) {
      try {
        await searchApi.deleteSearchHistory(id)
      } catch {
        // 搜索历史删除失败不影响本地体验
      }
    }
  }

  async function clear(): Promise<void> {
    items.value = []
    writeLocalHistory([])
    if (auth.isAuthenticated) {
      try {
        await searchApi.clearSearchHistory()
      } catch {
        // 同上
      }
    }
  }

  return { items, loading, hasHistory, usingLocalFallback, load, record, remove, clear, toLocalItems }
}
