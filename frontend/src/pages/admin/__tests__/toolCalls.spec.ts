import { describe, expect, it } from 'vitest'
import { isFetchCall, isWebSearchCall, parseToolCalls } from '@/pages/admin/toolCalls'

// 运行日志的可读性完全依赖这层解析：后端字段名一改，这里必须仍然给出
// 查询词 / 命中条数 / 前 3 条标题，所以四种形态与兜底路径都要锁住。
describe('parseToolCalls', () => {
  it('结构化 web_search：查询词、命中条数、前 3 条标题', () => {
    const calls = parseToolCalls({
      action: 'web_search',
      args: { query: '海岛建筑 热点', freshness: 'week' },
      summary: '联网检索完成',
      results: [
        { title: 'A 岛新地标', url: 'https://a.example/1' },
        { title: 'B 岛红石农场', url: 'https://b.example/2' },
        { title: 'C 岛交通改造', url: 'https://c.example/3' },
        { title: 'D 岛不该出现', url: 'https://d.example/4' },
      ],
    })
    expect(calls).toHaveLength(1)
    const [call] = calls
    expect(call.name).toBe('web_search')
    expect(call.query).toBe('海岛建筑 热点')
    expect(call.freshness).toBe('week')
    expect(call.hitCount).toBe(4)
    expect(call.titles).toEqual(['A 岛新地标', 'B 岛红石农场', 'C 岛交通改造'])
    expect(isWebSearchCall(call)).toBe(true)
    expect(isFetchCall(call)).toBe(false)
  })

  it('只有 prose summary 时从文本里抠命中条数与标题', () => {
    const calls = parseToolCalls({
      action: 'web_search',
      args: { query: '红石' },
      summary:
        '命中 6 条\n- [红石教程](https://e.example/1)\n- [红石电梯](https://e.example/2)',
    })
    expect(calls[0].hitCount).toBe(6)
    expect(calls[0].titles).toEqual(['红石教程', '红石电梯'])
  })

  it('认后端当前的 summary 头「联网搜索「q」（N 条）：」与「- [引擎] 标题 | url」行', () => {
    const calls = parseToolCalls({
      action: 'web_search',
      args: { query: '海岛建筑' },
      summary: [
        '联网搜索「海岛建筑」（2 条）：',
        '- [bing] A 岛新地标 | https://a.example/1',
        '  岛上的新地标，最近很火',
        '- [duckduckgo] B 岛红石农场 | https://b.example/2',
        '  发布：2026-11-01',
      ].join('\n'),
    })
    expect(calls[0].hitCount).toBe(2)
    // 摘要行与「发布」行不是标题，引擎前缀和 url 也不该混进来
    expect(calls[0].titles).toEqual(['A 岛新地标', 'B 岛红石农场'])
  })

  it('联网搜索降级到站内检索时，标题取「标题=」字段', () => {
    const calls = parseToolCalls({
      action: 'web_search',
      args: { query: '红石' },
      summary:
        '联网搜索未启用，已退回站内检索\n站内检索结果（1 条）：\n- id=01P 标题=我的红石农场 频道=生存 赞=3 评论=1',
    })
    expect(calls[0].hitCount).toBe(1)
    expect(calls[0].titles).toEqual(['我的红石农场'])
  })

  it('fetch_page：URL 与抓取字数（summary 兜底）', () => {
    const calls = parseToolCalls({
      action: 'fetch_page',
      args: { url: 'https://example.com/post' },
      summary: '抓取 1520 字',
    })
    expect(calls[0].url).toBe('https://example.com/post')
    expect(calls[0].charCount).toBe(1520)
    expect(isFetchCall(calls[0])).toBe(true)
  })

  it('fetch_page：后端只给「页面正文（url）：\\n正文」时按正文长度算字数', () => {
    const body = '岛'.repeat(321)
    const calls = parseToolCalls({
      action: 'fetch_page',
      args: { url: 'https://a.example/post' },
      summary: `页面正文（https://a.example/post）：\n${body}`,
    })
    expect(calls[0].charCount).toBe(321)
    // 正文不该被当成标题塞进列表
    expect(calls[0].titles).toEqual([])
  })

  it('fetch_page：正文以字段形式保存时按长度算字数', () => {
    const body = 'x'.repeat(321)
    const calls = parseToolCalls({ action: 'fetch_page', args: { url: 'https://a.example' }, result: { text: body } })
    expect(calls[0].charCount).toBe(321)
  })

  it('兼容 JSON 字符串、对象数组与 { calls: [] } 包装', () => {
    const arrayJson = JSON.stringify([
      { action: 'web_search', args: { query: 'a' }, results: [{ title: 'T1' }] },
      { action: 'fetch_page', args: { url: 'https://b.example' }, chars: 88 },
    ])
    const fromString = parseToolCalls(arrayJson)
    expect(fromString).toHaveLength(2)
    expect(fromString[0].titles).toEqual(['T1'])
    expect(fromString[1].charCount).toBe(88)

    const wrapped = parseToolCalls({ calls: [{ tool: 'web_search', input: { query: 'b' }, hitCount: 2 }] })
    expect(wrapped).toHaveLength(1)
    expect(wrapped[0].name).toBe('web_search')
    expect(wrapped[0].hitCount).toBe(2)
  })

  it('解析不出结构时保留原文，不丢信息', () => {
    expect(parseToolCalls(null)).toEqual([])
    expect(parseToolCalls('')).toEqual([])

    const calls = parseToolCalls('已点赞帖子 01ABC')
    expect(calls).toHaveLength(1)
    expect(calls[0].summary).toBe('已点赞帖子 01ABC')
    expect(calls[0].name).toBe('')
    expect(calls[0].raw).toBe('已点赞帖子 01ABC')

    const opaque = parseToolCalls({ foo: 'bar' })
    expect(opaque).toHaveLength(1)
    expect(opaque[0].raw).toEqual({ foo: 'bar' })
  })

  it('标题最多取 3 条', () => {
    const calls = parseToolCalls({
      action: 'web_search',
      results: Array.from({ length: 9 }, (_, i) => ({ title: `T${i}` })),
    })
    expect(calls[0].titles).toHaveLength(3)
  })

  it('两阶段日志：research 逐条展开，compose 排在最后', () => {
    const calls = parseToolCalls({
      research: [
        { action: 'web_search', query: 'minecraft 建筑', count: 10, results: [{ title: 'A' }] },
        { action: 'list_channel_posts', postIds: ['01P1'] },
      ],
      compose: { action: 'create_post', args: { title: '红石坑' }, outputId: '01POST' },
    })
    expect(calls.map((call) => call.name)).toEqual(['web_search', 'list_channel_posts', 'create_post'])
    expect(calls[0].query).toBe('minecraft 建筑')
    expect(calls[2].outputId).toBe('01POST')
  })

  it('两阶段日志：compose 是 JSON 字符串时也能解析', () => {
    const calls = parseToolCalls({
      research: [{ action: 'web_search', query: 'vrchat' }],
      compose: '{"action":"comment","args":{"postId":"01P1"}}',
    })
    expect(calls).toHaveLength(2)
    expect(calls[1].name).toBe('comment')
  })

  it('两阶段日志：research 为空数组时只渲染 compose', () => {
    const calls = parseToolCalls({ research: [], compose: { action: 'none' } })
    expect(calls).toHaveLength(1)
    expect(calls[0].name).toBe('none')
  })
})
