import { describe, expect, it, vi } from 'vitest'

// 「运行一次」要跑完整模型调用，必须单独放宽超时；这里锁住参数，防止以后被
// 顺手改回全局 20 秒（用户实测过：后端成功、前端报连接失败）。
const mocks = vi.hoisted(() => ({ post: vi.fn(), put: vi.fn(), get: vi.fn(), delete: vi.fn() }))

vi.mock('@/api/client', () => ({
  http: { post: mocks.post, put: mocks.put, get: mocks.get, delete: mocks.delete },
}))

import {
  isRequestTimeout,
  runAdminAgentNow,
  RUN_NOW_TIMEOUT_MS,
  updateAdminAgent,
} from '@/pages/admin/agentApi'

describe('runAdminAgentNow（超时单独放宽）', () => {
  it('用 180 秒超时且静默，避免全局 20 秒把成功当失败', async () => {
    mocks.post.mockResolvedValue({ data: { status: 'SUCCESS', run: { id: 'r1' } } })
    const result = await runAdminAgentNow('01AGENT')
    expect(mocks.post).toHaveBeenCalledWith(
      '/admin/agents/01AGENT/run-now',
      undefined,
      { timeout: RUN_NOW_TIMEOUT_MS, silent: true },
    )
    expect(RUN_NOW_TIMEOUT_MS).toBe(180_000)
    expect(result.status).toBe('SUCCESS')
    expect(result.run?.id).toBe('r1')
  })

  it('isRequestTimeout 认 axios 的超时码（包在 AppError.cause 里）', () => {
    expect(isRequestTimeout({ cause: { code: 'ECONNABORTED' } })).toBe(true)
    expect(isRequestTimeout({ cause: { code: 'ETIMEDOUT' } })).toBe(true)
    expect(isRequestTimeout({ cause: { code: 'ERR_NETWORK' } })).toBe(false)
    expect(isRequestTimeout(new Error('boom'))).toBe(false)
    expect(isRequestTimeout(null)).toBe(false)
  })
})

describe('updateAdminAgent（头像字段）', () => {
  it('传了 avatarUrl 就随人设一起提交', async () => {
    mocks.put.mockResolvedValue({ data: { id: 'p1', userId: 'u1' } })
    await updateAdminAgent('u1', { displayName: '小助手', avatarUrl: '/media/agent.png', profile: {} })
    expect(mocks.put.mock.calls[0][1]).toMatchObject({ avatarUrl: '/media/agent.png' })
  })

  it('没传 avatarUrl 时不覆盖已有头像', async () => {
    mocks.put.mockResolvedValue({ data: { id: 'p1', userId: 'u1' } })
    await updateAdminAgent('u1', { displayName: '小助手', profile: {} })
    expect(mocks.put.mock.calls[0][1]).not.toHaveProperty('avatarUrl')
  })

  it('avatarUrl 为 null 表示清空', async () => {
    mocks.put.mockResolvedValue({ data: { id: 'p1', userId: 'u1' } })
    await updateAdminAgent('u1', { displayName: '小助手', avatarUrl: null, profile: {} })
    expect(mocks.put.mock.calls[0][1]).toHaveProperty('avatarUrl', null)
  })
})
