import { describe, expect, it } from 'vitest'
import { normalizeAiMessage } from '@/api/ai'
import { toChannelActivity } from '@/api/activities'
import { toChannelApp } from '@/api/apps'

describe('normalizeAiMessage（视觉模型切换容错）', () => {
  it('images 为 null 时收敛成空数组，并保留后端提示', () => {
    const message = normalizeAiMessage({
      id: 'm1',
      role: 'ASSISTANT',
      content: '图片已忽略',
      images: null,
      sources: null,
      notice: '当前模型不支持图片，已忽略历史消息中的 2 张图片',
    })
    expect(message.images).toEqual([])
    expect(message.sources).toEqual([])
    expect(message.notice).toContain('不支持图片')
  })

  it('兼容 warning / systemNotice 字段名', () => {
    expect(normalizeAiMessage({ id: 'm2', warning: '已忽略图片' }).notice).toBe('已忽略图片')
    expect(normalizeAiMessage({ id: 'm3', systemNotice: '已忽略图片' }).notice).toBe('已忽略图片')
  })

  it('没有提示时 notice 为 null（不渲染空提示条）', () => {
    expect(normalizeAiMessage({ id: 'm4', images: ['/a.png'] }).notice).toBeNull()
    expect(normalizeAiMessage({ id: 'm4', images: ['/a.png'] }).images).toEqual(['/a.png'])
  })
})

describe('toChannelActivity（状态按时间兜底）', () => {
  const base = { id: 'a1', title: '夜聊', startsAt: '2026-11-16T20:00:00.000Z' }

  it('发布状态取服务端 status（DRAFT/PUBLISHED/CANCELLED）', () => {
    expect(toChannelActivity({ ...base, status: 'DRAFT' })?.publishState).toBe('DRAFT')
    expect(toChannelActivity({ ...base, status: 'CANCELLED' })?.publishState).toBe('CANCELLED')
    // 后端没给 status 时按已发布处理
    expect(toChannelActivity(base)?.publishState).toBe('PUBLISHED')
  })

  it('没给 status 时按时间推算：未来=UPCOMING，区间内=ONGOING，过去=ENDED', () => {
    const now = Date.now()
    expect(
      toChannelActivity({ ...base, startsAt: new Date(now + 3600_000).toISOString() })?.status,
    ).toBe('UPCOMING')
    expect(
      toChannelActivity({
        ...base,
        startsAt: new Date(now - 3600_000).toISOString(),
        endsAt: new Date(now + 3600_000).toISOString(),
      })?.status,
    ).toBe('ONGOING')
    expect(
      toChannelActivity({
        ...base,
        startsAt: new Date(now - 7200_000).toISOString(),
        endsAt: new Date(now - 3600_000).toISOString(),
      })?.status,
    ).toBe('ENDED')
  })

  it('缺少 id 或开始时间时返回 null，不把脏数据塞进列表', () => {
    expect(toChannelActivity({ title: 'x' })).toBeNull()
    expect(toChannelActivity({ id: 'a2' })).toBeNull()
  })
})

describe('toChannelApp（字段名容错）', () => {
  it('兼容 sortOrder / icon 字段', () => {
    const app = toChannelApp({ id: 'p1', name: '语音房', appType: 'VOICE_ROOM', sortOrder: 2, icon: '/i.png' })
    expect(app.sort).toBe(2)
    expect(app.iconUrl).toBe('/i.png')
    expect(app.enabled).toBe(true)
  })

  it('未知类型降级为 CUSTOM', () => {
    expect(toChannelApp({ id: 'p2', name: 'x', appType: 'WHATEVER' }).type).toBe('CUSTOM')
  })
})
