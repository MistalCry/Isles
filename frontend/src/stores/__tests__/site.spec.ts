import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

// 回归用例（用户实测 bug）：后台改完站点名，前台刷新必须立刻看到新值。
// 这里锁的是「store 不做时间窗缓存」：只有 force 才跳过请求，不存在 60 秒内读旧值的路径。
const mocks = vi.hoisted(() => ({
  get: vi.fn(),
}))

vi.mock('@/api/client', () => ({
  http: { get: mocks.get },
}))

import { useSiteStore } from '@/stores/site'
import { APP_NAME } from '@/constants/app'

function respond(values: Record<string, string>) {
  mocks.get.mockResolvedValueOnce({ data: { values } })
}

describe('site store 公开配置', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('load() 拉取 /platform/config 并映射成站点配置', async () => {
    respond({
      'site.name': '群岛',
      'site.logoUrl': '/uploads/logo.png',
      'site.icp': '京ICP备00000000号',
      'site.contactEmail': 'hi@isles.dev',
      'auth.register.enabled': 'false',
      'auth.register.requireInvite': 'true',
    })
    const store = useSiteStore()
    await store.load()

    expect(mocks.get).toHaveBeenCalledWith('/platform/config')
    expect(store.config.name).toBe('群岛')
    expect(store.config.logoUrl).toBe('/uploads/logo.png')
    expect(store.config.icp).toBe('京ICP备00000000号')
    expect(store.config.contactEmail).toBe('hi@isles.dev')
    expect(store.config.registrationEnabled).toBe(false)
    expect(store.config.registrationRequiresInvite).toBe(true)
  })

  it('已加载后普通 load() 不再请求（应用启动只拉一次）', async () => {
    respond({ 'site.name': '群岛' })
    const store = useSiteStore()
    await store.load()
    await store.load()
    expect(mocks.get).toHaveBeenCalledTimes(1)
  })

  it('load(true) 强制刷新：后台改完站点名后立即拿到新值，不存在 60 秒窗口', async () => {
    respond({ 'site.name': '群岛' })
    const store = useSiteStore()
    await store.load()
    expect(store.config.name).toBe('群岛')

    // 后台把 site.name 改成「群岛 Dev」
    respond({ 'site.name': '群岛 Dev' })
    await store.load(true)

    expect(mocks.get).toHaveBeenCalledTimes(2)
    expect(store.config.name).toBe('群岛 Dev')
  })

  it('新会话（整页刷新）重新拉取，不会沿用旧值', async () => {
    respond({ 'site.name': '群岛 Dev' })
    const store = useSiteStore()
    await store.load()
    expect(store.config.name).toBe('群岛 Dev')

    // 模拟整页刷新：新的 pinia 实例 + 新 store
    setActivePinia(createPinia())
    respond({ 'site.name': '群岛' })
    const fresh = useSiteStore()
    await fresh.load()
    expect(fresh.config.name).toBe('群岛')
  })

  it('接口不可用时回落内置默认值，不阻塞前台', async () => {
    mocks.get.mockRejectedValueOnce(new Error('network down'))
    const store = useSiteStore()
    await store.load()

    expect(store.config.name).toBe(APP_NAME)
    expect(store.config.registrationEnabled).toBe(true)
    expect(store.loaded).toBe(true)
  })

  it('空值字段回落默认：name 为空串时用内置站名', async () => {
    respond({ 'site.name': '   ' })
    const store = useSiteStore()
    await store.load()
    expect(store.config.name).toBe(APP_NAME)
  })
})
