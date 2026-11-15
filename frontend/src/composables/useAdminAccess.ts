import { computed, ref } from 'vue'
import { getAdminIdentity, type AdminIdentity } from '@/pages/admin/adminApi'

/**
 * 平台管理员身份（全局单例缓存）。
 *
 * <p>侧边栏要判断「是否显示进入后台」，但绝大多数用户不是管理员，
 * 因此只在登录后探测一次并缓存结果；后台内部仍以 `/admin/me` 的 `permissions` 为准。
 */
const identity = ref<AdminIdentity | null>(null)
const loaded = ref(false)
let pending: Promise<void> | null = null

async function ensure(force = false): Promise<void> {
  if (loaded.value && !force) return
  if (pending) return pending
  pending = (async () => {
    try {
      identity.value = await getAdminIdentity()
    } catch {
      identity.value = null
    } finally {
      loaded.value = true
      pending = null
    }
  })()
  return pending
}

/** 退出登录时清空，避免换账号后仍显示后台入口 */
function reset(): void {
  identity.value = null
  loaded.value = false
  pending = null
}

export function useAdminAccess() {
  return {
    identity,
    loaded,
    isAdmin: computed(() => Boolean(identity.value?.admin)),
    permissions: computed(() => identity.value?.permissions ?? []),
    ensure,
    reset,
  }
}
