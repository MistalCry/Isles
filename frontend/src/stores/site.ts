import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { http } from '@/api/client'
import { APP_NAME, appSlogan } from '@/constants/app'

/**
 * 平台公开配置（后端 `GET /api/v1/platform/config`）。
 *
 * 只包含 `publicVisible=true` 的项：站点名称/Logo/简介/备案号/联系邮箱、注册开关。
 * 后端允许缓存 60 秒，因此这里只在应用启动时拉一次；失败时静默回落到内置常量，
 * 不让「配置接口挂了」演变成「整站白屏」。
 */
export interface SiteConfig {
  name: string
  logoUrl: string
  description: string
  icp: string
  contactEmail: string
  /** 是否开放注册（auth.register.enabled） */
  registrationEnabled: boolean
  /** 注册是否需要邀请码（auth.register.requireInvite） */
  registrationRequiresInvite: boolean
}

const FALLBACK: SiteConfig = {
  name: APP_NAME,
  logoUrl: '',
  description: '',
  icp: '',
  contactEmail: '',
  registrationEnabled: true,
  registrationRequiresInvite: false,
}

export const useSiteStore = defineStore('site', () => {
  const values = ref<Record<string, string>>({})
  const loaded = ref(false)
  const loading = ref(false)

  const config = computed<SiteConfig>(() => {
    const raw = values.value
    return {
      name: raw['site.name']?.trim() || FALLBACK.name,
      logoUrl: raw['site.logoUrl']?.trim() ?? FALLBACK.logoUrl,
      description: raw['site.description']?.trim() || appSlogan(),
      icp: raw['site.icp']?.trim() ?? FALLBACK.icp,
      contactEmail: raw['site.contactEmail']?.trim() ?? FALLBACK.contactEmail,
      // 后端用字符串 "true"/"false" 表达布尔，缺省视为开放注册
      registrationEnabled: raw['auth.register.enabled'] !== 'false',
      registrationRequiresInvite: raw['auth.register.requireInvite'] === 'true',
    }
  })

  async function load(force = false): Promise<void> {
    if (loading.value || (loaded.value && !force)) return
    loading.value = true
    try {
      const { data } = await http.get<{ values?: Record<string, string> }>('/platform/config')
      values.value = data?.values ?? {}
    } catch {
      // 配置接口不可用时保持默认值：前台仍可浏览，不影响核心流程
      values.value = {}
    } finally {
      loaded.value = true
      loading.value = false
    }
  }

  return { values, loaded, loading, config, load }
})
