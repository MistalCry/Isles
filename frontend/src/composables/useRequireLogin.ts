import { t } from '@/i18n'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/** 写操作登录守卫：未登录时提示并跳转登录页，登录后回到当前页面。 */
export function useRequireLogin() {
  const router = useRouter()
  const auth = useAuthStore()

  function requireLogin(message = t('auth.loginRequired')): boolean {
    if (auth.isAuthenticated) return true
    ElMessage.warning(message)
    const current = router.currentRoute.value
    void router.push({ name: 'login', query: { redirect: current.fullPath } })
    return false
  }

  return { requireLogin }
}
