#!/usr/bin/env node
/**
 * AI 工具调用回归（P1）：mock-openai 支持 tools，验证
 *   1. 「模型要工具 → 检索 → 二轮回答」整条链路，且来源引用来自真实命中的帖子；
 *   2. 模型不支持 function calling（上游 400）时自动降级为关键词检索，问答仍正常。
 *
 * 依赖：
 *   - mock-openai 在 1235 端口（`node tools/mock-openai/server.mjs 1235`），
 *     其 /debug/requests 提供请求摘要，用来断言轮次与「服务端实际用了哪个模型」；
 *   - 平台超管账号（默认 admin_demo / Passw0rd123），用于临时创建/删除模型配置。
 *
 * 脚本自造数据：临时模型配置（跑完删除）、测试用户、公开频道与帖子（跑完解散）。
 * 不动平台默认模型配置。
 *
 * 运行：
 *   MOCK_URL=http://127.0.0.1:1235 BASE_URL=http://127.0.0.1:8082/api/v1 node tools/e2e/ai-tools-regression.mjs
 */

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1'
const MOCK_URL = (process.env.MOCK_URL || 'http://127.0.0.1:1235').replace(/\/$/, '')
const ADMIN_USERNAME = process.env.ADMIN_USERNAME || 'admin_demo'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Passw0rd123'

const results = []
const cleanup = { modelIds: [], conversationIds: [], channelIds: [], ownerToken: null }

class HttpError extends Error {
  constructor(status, body, label) {
    super(`${status} ${label}: ${typeof body === 'string' ? body : JSON.stringify(body)}`)
    this.status = status
    this.body = body
  }
}

async function api(method, path, { token, body, expect = [200], base = BASE_URL } = {}) {
  const headers = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`
  const response = await fetch(`${base}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  let data = text
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    // 非 JSON 保留原文
  }
  if (!expect.includes(response.status)) {
    throw new HttpError(response.status, data, `${method} ${path}`)
  }
  return data
}

function assert(condition, message) {
  if (!condition) throw new Error(`断言失败：${message}`)
}

let blockedReason = null

function skip(name, reason) {
  results.push({ name, status: 'skip', note: reason })
  console.log(`  ⊘ ${name}\n    跳过：${reason}`)
}

async function step(name, fn) {
  if (blockedReason) {
    results.push({ name, status: 'skip', note: blockedReason })
    console.log(`  ⊘ ${name}\n    跳过：${blockedReason}`)
    return
  }
  try {
    await fn()
    results.push({ name, status: 'pass' })
    console.log(`  ✓ ${name}`)
  } catch (error) {
    results.push({ name, status: 'fail', error: error.message })
    console.error(`  ✗ ${name}\n    ${error.message}`)
  }
}

function suffix() {
  return `${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`
}

async function newUser(prefix) {
  const username = `${prefix}_${suffix()}`
  const password = 'Passw0rd123'
  await api('POST', '/auth/register', { expect: [201], body: { username, password, nickname: prefix } })
  const login = await api('POST', '/auth/login', { body: { username, password } })
  return { username, id: login.user.id, token: login.accessToken }
}

/** 把 SSE 文本解析成 [{event, data}] */
function parseSse(text) {
  const events = []
  for (const block of text.split('\n\n')) {
    const lines = block.split('\n')
    let event = 'message'
    const dataLines = []
    for (const line of lines) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
    }
    if (dataLines.length === 0) continue
    const raw = dataLines.join('\n')
    let data = raw
    try {
      data = JSON.parse(raw)
    } catch {
      // 非 JSON 数据保留原文
    }
    events.push({ event, data })
  }
  return events
}

/** sources 事件载荷：{ sources: [...], toolRounds } */
function sourcesOf(event) {
  const payload = event?.data ?? {}
  return payload.sources ?? payload.items ?? []
}

async function askStream(token, conversationId, content) {
  const response = await fetch(`${BASE_URL}/ai/conversations/${conversationId}/messages/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ content, imageUrls: [], deepThinking: false }),
  })
  const text = await response.text()
  assert(response.status === 200, `流式接口应 200，实际 ${response.status} ${text.slice(0, 200)}`)
  return parseSse(text)
}

async function mockRequests() {
  const data = await api('GET', '/debug/requests', { base: MOCK_URL })
  return data.items ?? []
}

async function resetMockRequests() {
  await api('DELETE', '/debug/requests', { base: MOCK_URL })
}

async function runCleanup() {
  console.log('\n清理：')
  for (const id of cleanup.conversationIds) {
    try {
      await api('DELETE', `/ai/conversations/${id}`, { token: cleanup.userToken, expect: [204, 404] })
    } catch (error) {
      console.error(`  ! 删除会话失败 ${id}：${error.message}`)
    }
  }
  if (cleanup.conversationIds.length > 0) console.log('  ✓ 测试会话已删除')
  for (const id of cleanup.modelIds) {
    try {
      await api('DELETE', `/admin/ai/models/${id}`, { token: cleanup.adminToken, expect: [204, 404] })
    } catch (error) {
      console.error(`  ! 删除模型配置失败 ${id}：${error.message}`)
    }
  }
  if (cleanup.modelIds.length > 0) console.log('  ✓ 临时模型配置已删除')
  for (const channelId of cleanup.channelIds) {
    try {
      await api('POST', `/channels/${channelId}/dissolve`, {
        token: cleanup.ownerToken,
        expect: [204, 404],
      })
      console.log(`  ✓ 测试频道已解散 ${channelId}`)
    } catch (error) {
      console.error(`  ! 解散频道失败 ${channelId}：${error.message}`)
    }
  }
}

async function main() {
  console.log(`\n群岛 AI 工具调用回归 · ${BASE_URL} · mock=${MOCK_URL}\n`)

  const health = await fetch(`${BASE_URL.replace(/\/api\/v1$/, '')}/actuator/health`).catch(() => null)
  if (!health || !health.ok) {
    console.error('后端不可用，请先启动后端')
    process.exit(1)
  }
  const mockHealth = await fetch(`${MOCK_URL}/health`).catch(() => null)
  if (!mockHealth || !mockHealth.ok) {
    console.error(`mock-openai 不可用（${MOCK_URL}），先执行：node tools/mock-openai/server.mjs 1235`)
    process.exit(1)
  }

  let admin
  let user
  let channel
  let emptyChannel
  let board
  let post
  let toolsModel
  let noToolsModel
  let toolOffModel
  /** 工具模式下 SSE sources 事件的来源 id，供「与落库一致」用例比对 */
  let toolSourceIds = []
  const keyword = `E2E工具词${suffix()}`

  await step('准备：超管登录 + 三个临时模型配置（工具开 / 上游不支持 / 工具关）', async () => {
    const login = await api('POST', '/auth/login', { body: { username: ADMIN_USERNAME, password: ADMIN_PASSWORD } })
    admin = { token: login.accessToken, id: login.user.id }
    cleanup.adminToken = admin.token

    const base = {
      baseUrl: `${MOCK_URL}/v1`,
      apiKey: '',
      visionEnabled: false,
      reasoningEnabled: false,
      thinkingMode: 'NONE',
      isDefault: false,
      enabled: true,
      toolCallEnabled: true,
      timeoutSeconds: 30,
      maxTokens: 512,
    }
    toolsModel = await api('POST', '/admin/ai/models', {
      token: admin.token,
      expect: [201],
      body: { ...base, name: `E2E Mock Tools ${suffix()}`, model: 'mock-chat' },
    })
    cleanup.modelIds.push(toolsModel.id)
    noToolsModel = await api('POST', '/admin/ai/models', {
      token: admin.token,
      expect: [201],
      body: { ...base, name: `E2E Mock NoTools ${suffix()}`, model: 'mock-no-tools' },
    })
    cleanup.modelIds.push(noToolsModel.id)
    toolOffModel = await api('POST', '/admin/ai/models', {
      token: admin.token,
      expect: [201],
      body: { ...base, name: `E2E Mock ToolOff ${suffix()}`, model: 'mock-chat', toolCallEnabled: false },
    })
    cleanup.modelIds.push(toolOffModel.id)
    assert(toolsModel.toolCallEnabled === true, '临时模型应开启工具调用')
    assert(toolOffModel.toolCallEnabled === false, '工具关闭的模型不应开启工具调用')
  })

  await step('准备：用户 + 公开频道 + 含唯一关键词的帖子', async () => {
    user = await newUser('aitool')
    cleanup.userToken = user.token
    channel = await api('POST', '/channels', {
      token: user.token,
      expect: [201],
      body: { name: `AI Tools ${suffix()}`, avatarUrl: '/uploads/default-avatar.png', type: 'PUBLIC' },
    })
    cleanup.channelIds.push(channel.id)
    cleanup.ownerToken = user.token
    const boards = await api('GET', `/channels/${channel.id}/boards`)
    board = boards.items[0]
    post = await api('POST', `/channels/${channel.id}/posts`, {
      token: user.token,
      expect: [201],
      body: {
        boardId: board.id,
        title: `${keyword} 岛屿生活指南`,
        content: `${keyword} 是本次检索的唯一标记。这里记录了群岛的潮汐、灯塔与夜航注意事项。`,
        contentFormat: 'PLAIN',
      },
    })
    // 空频道：检索必然 0 命中（关键词兜底也是空），用来验证「0 命中也要发 sources 空数组」
    emptyChannel = await api('POST', '/channels', {
      token: user.token,
      expect: [201],
      body: { name: `AI Tools Empty ${suffix()}`, avatarUrl: '/uploads/default-avatar.png', type: 'PUBLIC' },
    })
    cleanup.channelIds.push(emptyChannel.id)
  })

  if (!post || !toolsModel) {
    blockedReason = '前置准备失败（模型配置/帖子未创建），常见原因：注册限流 429 或 AI 配置接口不可用'
  }

  await step('工具调用：模型要工具 → 检索 → 二轮回答，来源引用来自真实命中', async () => {
    await resetMockRequests()
    const conversation = await api('POST', '/ai/conversations', {
      token: user.token,
      expect: [201],
      body: { scope: 'GLOBAL', title: `工具调用 ${suffix()}`, modelConfigId: toolsModel.id },
    })
    cleanup.conversationIds.push(conversation.id)

    const events = await askStream(user.token, conversation.id, `${keyword} 这个帖子讲了什么？`)
    const names = events.map((item) => item.event)
    assert(names.includes('meta'), 'SSE 应包含 meta 事件')
    assert(names.includes('delta'), 'SSE 应包含 delta 事件')
    assert(names.includes('done'), 'SSE 应包含 done 事件')
    assert(!names.includes('error'), `SSE 不应包含 error 事件，实际 ${JSON.stringify(events.find((e) => e.event === 'error')?.data)}`)

    const content = events
      .filter((item) => item.event === 'delta')
      .map((item) => (typeof item.data === 'string' ? item.data : item.data?.content ?? ''))
      .join('')
    assert(content.length > 0, '应收到非空回答内容')
    assert(content.includes('调用了'), `回答应说明调用了工具，实际：${content.slice(0, 120)}`)

    const sourcesEvent = events.find((item) => item.event === 'sources')
    assert(sourcesEvent, '工具模式下必须下发 sources 事件（前端据此整体替换 meta 兜底来源）')
    assert(
      (sourcesEvent.data?.toolRounds ?? 0) >= 1,
      `工具模式下 sources 事件应带 toolRounds，实际 ${sourcesEvent.data?.toolRounds}`,
    )
    const sources = sourcesOf(sourcesEvent)
    toolSourceIds = sources.map((item) => item.id).sort()
    assert(Array.isArray(sources) && sources.length > 0, '来源引用不应为空')
    assert(
      sources.some((item) => item.id === post.id),
      `来源引用应包含真实命中的帖子 ${post.id}，实际 ${sources.map((item) => item.id).join(',')}`,
    )
    assert(
      sources.some((item) => (item.title ?? '').includes(keyword)),
      '来源标题应来自真实帖子标题',
    )

    const log = await mockRequests()
    assert(log.length >= 2, `工具调用应至少两轮请求，实际 ${log.length} 轮`)
    const first = log[0]
    const second = log[1]
    assert(first.tools.includes('search_global_posts'), `首轮应下发检索工具，实际 ${first.tools.join(',')}`)
    assert(first.toolResultCount === 0, '首轮不应携带 tool 结果')
    assert(second.toolResultCount > 0, `二轮应携带 tool 结果，实际 ${second.toolResultCount}`)
  })

  await step('工具调用：来源引用与消息落库一致（刷新后仍能看到引用）', async () => {
    const conversationId = cleanup.conversationIds[0]
    const messages = await api('GET', `/ai/conversations/${conversationId}/messages?limit=20`, { token: user.token })
    const assistant = (messages.items ?? []).filter((item) => item.role === 'ASSISTANT').at(-1)
    assert(assistant, '应存在助手消息')
    assert((assistant.content ?? '').length > 0, '助手消息内容应落库')
    const stored = assistant.sources ?? []
    assert(
      stored.some((item) => item.id === post.id),
      `落库来源应包含真实命中帖子，实际 ${JSON.stringify(stored).slice(0, 200)}`,
    )
    // SSE 下发的来源与落库的来源必须一致（刷新后看到的与当场看到的是同一批）
    const storedIds = stored.map((item) => item.id).sort()
    assert(
      JSON.stringify(storedIds) === JSON.stringify(toolSourceIds),
      `SSE 来源与落库来源应一致：SSE=${toolSourceIds.join(',')} 落库=${storedIds.join(',')}`,
    )
  })

  await step('工具模式 0 命中：仍下发 sources 事件且为空数组', async () => {
    // 必须用「没有任何帖子」的频道：关键词检索在 0 命中时会兜底返回最新帖子，
    // 只有在空频道里才能构造出真正的 0 命中（兜底也是空）。
    const conversation = await api('POST', '/ai/conversations', {
      token: user.token,
      expect: [201],
      body: {
        scope: 'CHANNEL',
        channelId: emptyChannel.id,
        title: `零命中 ${suffix()}`,
        modelConfigId: toolsModel.id,
      },
    })
    cleanup.conversationIds.push(conversation.id)

    const events = await askStream(user.token, conversation.id, '这个频道里有人聊过什么吗？')
    const names = events.map((item) => item.event)
    assert(!names.includes('error'), '零命中不应报错')
    assert(names.includes('done'), '零命中仍应正常结束')

    const sourcesEvent = events.find((item) => item.event === 'sources')
    assert(sourcesEvent, '零命中时也必须下发 sources 事件（否则前端会一直显示 meta 兜底来源）')
    const sources = sourcesOf(sourcesEvent)
    assert(Array.isArray(sources), 'sources 事件载荷应为数组')
    assert(sources.length === 0, `零命中时 sources 应为空数组，实际 ${sources.length} 条`)
    assert((sourcesEvent.data?.toolRounds ?? 0) >= 1, '零命中仍应记录工具轮次')

    const messages = await api('GET', `/ai/conversations/${conversation.id}/messages?limit=20`, { token: user.token })
    const assistant = (messages.items ?? []).filter((item) => item.role === 'ASSISTANT').at(-1)
    assert(assistant, '应存在助手消息')
    assert((assistant.sources ?? []).length === 0, '零命中的落库来源应为空')
  })

  await step('关闭工具调用：不下发 tools，但仍下发 sources（关键词检索结果）', async () => {
    await resetMockRequests()
    const conversation = await api('POST', '/ai/conversations', {
      token: user.token,
      expect: [201],
      body: { scope: 'GLOBAL', title: `无工具 ${suffix()}`, modelConfigId: toolOffModel.id },
    })
    cleanup.conversationIds.push(conversation.id)

    const events = await askStream(user.token, conversation.id, `${keyword} 讲讲这个帖子`)
    const names = events.map((item) => item.event)
    assert(!names.includes('error'), '关闭工具调用不应报错')
    assert(names.includes('meta'), '应下发 meta 事件')

    const log = await mockRequests()
    assert(log.length >= 1, '应至少有一次模型请求')
    assert(log.every((item) => item.tools.length === 0), '关闭工具调用时请求不应带 tools')

    // 契约（第 13 轮定稿）：sources 是来源引用的唯一信号，后端每次回答都下发；
    // 非工具模式同样要发，前端不再依赖 meta.sources 兜底（meta 只作为占位来源）。
    const sourcesEvent = events.find((item) => item.event === 'sources')
    assert(sourcesEvent, '非工具模式也必须下发 sources 事件（前端以它为准，不再等 meta 兜底）')
    const finalSources = sourcesOf(sourcesEvent)
    assert(
      finalSources.some((item) => item.id === post.id),
      `非工具模式的 sources 应命中测试帖 ${post.id}，实际 ${finalSources.map((item) => item.id).join(',')}`,
    )
    const metaSources = events.find((item) => item.event === 'meta')?.data?.sources ?? []
    assert(metaSources.length > 0, 'meta.sources 仍应带关键词检索结果（首屏占位用）')
  })

  await step('降级：模型不支持 tools（上游 400）时回落关键词检索，问答仍正常', async () => {
    await resetMockRequests()
    const conversation = await api('POST', '/ai/conversations', {
      token: user.token,
      expect: [201],
      body: { scope: 'GLOBAL', title: `降级 ${suffix()}`, modelConfigId: noToolsModel.id },
    })
    cleanup.conversationIds.push(conversation.id)

    const events = await askStream(user.token, conversation.id, `${keyword} 最近有什么讨论？`)
    const names = events.map((item) => item.event)
    assert(names.includes('delta'), '降级后仍应有 delta 事件')
    assert(names.includes('done'), '降级后仍应有 done 事件')
    assert(
      !names.includes('error'),
      `降级不应向前端报错，实际 ${JSON.stringify(events.find((e) => e.event === 'error')?.data)}`,
    )
    const content = events
      .filter((item) => item.event === 'delta')
      .map((item) => (typeof item.data === 'string' ? item.data : item.data?.content ?? ''))
      .join('')
    assert(content.length > 0, '降级后回答不应为空')

    const log = await mockRequests()
    assert(log.length >= 2, `降级应产生「带工具失败 → 不带工具重试」两次请求，实际 ${log.length}`)
    assert(log[0].tools.length > 0, '第一次请求应带工具（随后被上游 400 拒绝）')
    assert(log[1].tools.length === 0, `重试请求不应再带工具，实际 ${log[1].tools.join(',')}`)
  })

  const passed = results.filter((item) => item.status === 'pass').length
  const failed = results.filter((item) => item.status === 'fail').length
  const skipped = results.filter((item) => item.status === 'skip').length
  console.log(`\n结果：${passed} 通过 / ${failed} 失败 / ${skipped} 跳过（共 ${results.length} 项）\n`)
  if (failed > 0) {
    console.log('失败项：')
    results.filter((item) => item.status === 'fail').forEach((item) => console.log(`  - ${item.name}\n    ${item.error}`))
    console.log('')
  }
  return failed
}

let exitCode = 1
try {
  const failed = await main()
  exitCode = failed > 0 ? 1 : 0
} catch (error) {
  console.error(`\n回归脚本异常：${error.message}\n`)
} finally {
  await runCleanup()
}
process.exit(exitCode)
