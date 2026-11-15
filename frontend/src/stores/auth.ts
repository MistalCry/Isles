import { t } from '@/i18n'
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api'
import { setAccessToken } from '@/api/token'
import type { CurrentUser, LoginPayload, RegisterPayload } from '@/types/models'

/**
 * 认证状态：accessToken 仅存内存（刷新页面靠 HttpOnly refresh cookie 恢复），
 * currentUser 为全站共享的当前用户信息（docs/02 §6.2）。
 */
export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(null)
  const currentUser = ref<CurrentUser | null>(null)
  const isReady = ref(false)

  let bootstrapTask: Promise<void> | null = null

  const isAuthenticated = computed(() => Boolean(accessToken.value && currentUser.value))
  const displayName = computed(() => currentUser.value?.nickname || currentUser.value?.username || t('auth.guest'))

  function applyToken(token: string | null): void {
    accessToken.value = token
    setAccessToken(token)
  }

  function clearSession(): void {
    applyToken(null)
    currentUser.value = null
  }

  async function fetchMe(): Promise<CurrentUser> {
    const user = await authApi.getMe()
    currentUser.value = user
    return user
  }

  async function refresh(): Promise<string | null> {
    try {
      const token = await authApi.refreshAccessToken()
      applyToken(token)
      return token
    } catch {
      clearSession()
      return null
    }
  }

  async function login(payload: LoginPayload): Promise<CurrentUser> {
    const response = await authApi.login(payload)
    applyToken(response.accessToken)
    if (response.user) {
      currentUser.value = response.user
      return response.user
    }
    return fetchMe()
  }

  async function register(payload: RegisterPayload): Promise<CurrentUser> {
    await authApi.register(payload)
    return login({ username: payload.username, password: payload.password })
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch {
      // 登出接口失败也必须清理本地会话，避免卡在“假登录”状态
    } finally {
      clearSession()
    }
  }

  /** 应用启动时执行一次：尝试用 refresh cookie 恢复会话，失败静默视为游客。 */
  async function bootstrap(): Promise<void> {
    if (isReady.value) return
    if (!bootstrapTask) {
      bootstrapTask = (async () => {
        try {
          if (!accessToken.value) {
            await refresh()
          }
          if (accessToken.value && !currentUser.value) {
            await fetchMe()
          }
        } catch {
          clearSession()
        } finally {
          isReady.value = true
          bootstrapTask = null
        }
      })()
    }
    await bootstrapTask
  }

  return {
    accessToken,
    currentUser,
    isReady,
    isAuthenticated,
    displayName,
    fetchMe,
    login,
    register,
    refresh,
    logout,
    clearSession,
    bootstrap,
  }
})
