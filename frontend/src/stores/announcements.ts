import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { listActiveAnnouncements, type AdminAnnouncement } from '@/pages/admin/adminApi'

const DISMISSED_KEY = 'isles.announcements.dismissed'

function readDismissed(): string[] {
  if (typeof window === 'undefined') return []
  try {
    const raw = window.localStorage.getItem(DISMISSED_KEY)
    const parsed = raw ? JSON.parse(raw) : []
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === 'string') : []
  } catch {
    return []
  }
}

function writeDismissed(ids: string[]): void {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(DISMISSED_KEY, JSON.stringify(ids))
  } catch {
    // 隐私模式/配额满时静默失败：横幅下次仍会出现，不影响主流程
  }
}

/**
 * 平台公告（公开接口 `/platform/announcements`）。
 *
 * <p>只返回生效中的公告；关闭状态按公告 id 记在 localStorage，
 * 用户菜单里的「公告」可以重新打开查看全部生效公告（用户决策：关了也要能找回来）。
 */
export const useAnnouncementStore = defineStore('announcements', () => {
  const items = ref<AdminAnnouncement[]>([])
  const dismissedIds = ref<string[]>(readDismissed())
  const loaded = ref(false)
  const loading = ref(false)

  /** 顶部横幅只展示 placement=BANNER 且未被关闭的公告 */
  const bannerItems = computed(() =>
    items.value.filter((item) => item.placement === 'BANNER' && !dismissedIds.value.includes(item.id)),
  )

  /** 用户菜单「公告」里展示全部生效公告（含已关闭的，便于找回） */
  const activeItems = computed(() => items.value)

  async function load(force = false): Promise<void> {
    if (loading.value || (loaded.value && !force)) return
    loading.value = true
    try {
      items.value = await listActiveAnnouncements()
    } catch {
      items.value = []
    } finally {
      loaded.value = true
      loading.value = false
    }
  }

  function dismiss(id: string): void {
    if (dismissedIds.value.includes(id)) return
    dismissedIds.value = [...dismissedIds.value, id]
    writeDismissed(dismissedIds.value)
  }

  function restoreAll(): void {
    dismissedIds.value = []
    writeDismissed([])
  }

  return { items, bannerItems, activeItems, dismissedIds, loaded, loading, load, dismiss, restoreAll }
})
