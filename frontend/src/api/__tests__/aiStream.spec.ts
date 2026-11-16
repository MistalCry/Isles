import { afterEach, describe, expect, it, vi } from 'vitest'
import { streamChat, type StreamHandlers } from '@/api/ai'

/**
 * SSE 事件分发回归：后端在工具调用模式下会先推 meta（含关键词兜底 sources），
 * 检索完成后再推 sources（真实命中）。前端必须把 sources 事件分发出去，
 * 否则界面只能看到 meta 里那批「错的」来源。
 */

function sseBlock(event: string, payload: unknown): string {
  return `event: ${event}\ndata: ${JSON.stringify(payload)}\n\n`
}

function mockStream(chunks: string[]): void {
  const encoder = new TextEncoder()
  const reader = {
    index: 0,
    async read() {
      if (this.index >= chunks.length) return { done: true, value: undefined }
      const value = encoder.encode(chunks[this.index++])
      return { done: false, value }
    },
  }
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'text/event-stream' }),
      body: { getReader: () => reader },
    }),
  )
}

function collect() {
  const events: Array<{ type: string; payload: unknown }> = []
  const handlers: StreamHandlers = {
    onMeta: (meta) => events.push({ type: 'meta', payload: meta }),
    onSources: (payload) => events.push({ type: 'sources', payload }),
    onDelta: (content) => events.push({ type: 'delta', payload: content }),
    onDone: (done) => events.push({ type: 'done', payload: done }),
    onError: (error) => events.push({ type: 'error', payload: error }),
  }
  return { events, handlers }
}

const KEYWORD_SOURCES = [{ postId: '01POSTKEYWORD000000000000', title: '关键词兜底' }]
const TOOL_SOURCES = [{ postId: '01POSTTOOL00000000000000', title: '工具真实命中' }]

const META = {
  conversationId: '01CONV000000000000000000',
  messageId: '01MSG0000000000000000000',
  model: 'qwen3-vl-8b-instruct',
  sources: KEYWORD_SOURCES,
  deepThinking: false,
}

const DONE = { messageId: '01MSG0000000000000000000', aborted: false, status: 'COMPLETED' }

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('AI 流式来源事件', () => {
  it('工具调用模式：sources 事件被分发，且排在 meta 之后、done 之前', async () => {
    mockStream([
      sseBlock('meta', META),
      sseBlock('delta', { content: '正在检索…' }),
      sseBlock('sources', { sources: TOOL_SOURCES, toolRounds: 2 }),
      sseBlock('delta', { content: '答案' }),
      sseBlock('done', DONE),
    ])
    const { events, handlers } = collect()
    await streamChat('01CONV000000000000000000', { content: '问点什么' }, handlers)

    expect(events.map((item) => item.type)).toEqual(['meta', 'delta', 'sources', 'delta', 'done'])
    const sourcesEvent = events.find((item) => item.type === 'sources')
    expect(sourcesEvent?.payload).toEqual({ sources: TOOL_SOURCES, toolRounds: 2 })
  })

  it('未开工具调用：没有 sources 事件，meta 里带关键词来源', async () => {
    mockStream([sseBlock('meta', META), sseBlock('delta', { content: '答案' }), sseBlock('done', DONE)])
    const { events, handlers } = collect()
    await streamChat('01CONV000000000000000000', { content: '问点什么' }, handlers)

    expect(events.map((item) => item.type)).toEqual(['meta', 'delta', 'done'])
    const metaEvent = events.find((item) => item.type === 'meta')
    expect((metaEvent?.payload as { sources: unknown[] }).sources).toEqual(KEYWORD_SOURCES)
  })

  it('sources 事件里的空数组也会被分发（用于清空旧来源）', async () => {
    mockStream([sseBlock('meta', META), sseBlock('sources', { sources: [] }), sseBlock('done', DONE)])
    const { events, handlers } = collect()
    await streamChat('01CONV000000000000000000', { content: '问点什么' }, handlers)

    const sourcesEvent = events.find((item) => item.type === 'sources')
    expect(sourcesEvent?.payload).toEqual({ sources: [], toolRounds: undefined })
  })
})
