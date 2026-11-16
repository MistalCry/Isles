#!/usr/bin/env node
/**
 * 用户中心与 AI 会话回归（第 14 轮）：
 *
 * 1. 我的帖子：`GET /users/me/posts` 只返回自己的帖子、按时间倒序、`channelId` 过滤准确。
 * 2. 助手多会话：同一用户在同一频道可建多个 `CHANNEL_ASSISTANT` 会话，消息互不串；非成员不能建。
 * 3. 视觉模型兼容：带图历史 + 无视觉模型提问 → 不报 400、回答正常，且服务端明确告知「已忽略 N 张图片」。
 *
 * 视觉用例通过 mock-openai 的 /debug/requests 读系统提示词来断言「忽略提示」真的下发了。
 *
 * 运行：
 *   MOCK_URL=http://127.0.0.1:1235 BASE_URL=http://127.0.0.1:8082/api/v1 node tools/e2e/user-ai-regression.mjs
 */

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1'
const MOCK_URL = (process.env.MOCK_URL || 'http://127.0.0.1:1235').replace(/\/$/, '')
const ADMIN_USERNAME = process.env.ADMIN_USERNAME || 'admin_demo'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Passw0rd123'

/** 1x1 透明 PNG：可直接被 ImageIO 解码，上传后能拿到可解析的 /uploads 地址 */
const TINY_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
)

const results = []
const cleanup = { channelIds: [], ownerToken: null, modelIds: [], conversationIds: [], userToken: null }

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
    skip(name, blockedReason)
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

function parseSse(text) {
  const events = []
  for (const block of text.split('\n\n')) {
    let event = 'message'
    const dataLines = []
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
    }
    if (dataLines.length === 0) continue
    const raw = dataLines.join('\n')
    let data = raw
    try {
      data = JSON.parse(raw)
    } catch {
      // 保留原文
    }
    events.push({ event, data })
  }
  return events
}

async function askStream(token, conversationId, content, imageUrls = []) {
  const response = await fetch(`${BASE_URL}/ai/conversations/${conversationId}/messages/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ content, imageUrls, deepThinking: false }),
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
  console.log(`\n群岛用户中心 / AI 会话回归 · ${BASE_URL}\n`)

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
  let owner
  let member
  let outsider
  let channelA
  let channelB
  let boardA
  let boardB
  let textModel

  await step('准备：超管 + 三个账号 + 两个频道（用于 channelId 过滤）', async () => {
    const login = await api('POST', '/auth/login', { body: { username: ADMIN_USERNAME, password: ADMIN_PASSWORD } })
    admin = { token: login.accessToken }
    cleanup.adminToken = admin.token

    owner = await newUser('uaown')
    member = await newUser('uamem')
    outsider = await newUser('uaout')
    cleanup.userToken = owner.token

    channelA = await api('POST', '/channels', {
      token: owner.token,
      expect: [201],
      body: { name: `UA Alpha ${suffix()}`, avatarUrl: '/uploads/default-avatar.png', type: 'PUBLIC' },
    })
    cleanup.channelIds.push(channelA.id)
    cleanup.ownerToken = owner.token
    channelB = await api('POST', '/channels', {
      token: owner.token,
      expect: [201],
      body: { name: `UA Beta ${suffix()}`, avatarUrl: '/uploads/default-avatar.png', type: 'PUBLIC' },
    })
    cleanup.channelIds.push(channelB.id)
    boardA = (await api('GET', `/channels/${channelA.id}/boards`)).items[0]
    boardB = (await api('GET', `/channels/${channelB.id}/boards`)).items[0]
    await api('POST', `/channels/${channelA.id}/join`, { token: member.token })
    await api('POST', `/channels/${channelB.id}/join`, { token: member.token })
  })

  if (!channelA) {
    blockedReason = '前置准备失败（账号/频道未创建），常见原因：注册限流 429'
  }

  // ------------------------------------------------------------------
  // 一、我的帖子
  // ------------------------------------------------------------------
  const created = []

  await step('我的帖子：只返回自己的、按时间倒序、channelId 过滤准确', async () => {
    // 另一个账号也发帖，用来验证「只返回自己的」
    await api('POST', `/channels/${channelA.id}/posts`, {
      token: member.token,
      expect: [201],
      body: { boardId: boardA.id, title: `别人的帖子 ${suffix()}`, content: '别人的内容', contentFormat: 'PLAIN' },
    })

    const first = await api('POST', `/channels/${channelA.id}/posts`, {
      token: owner.token,
      expect: [201],
      body: { boardId: boardA.id, title: `我的 A1 ${suffix()}`, content: 'A1', contentFormat: 'PLAIN' },
    })
    const second = await api('POST', `/channels/${channelB.id}/posts`, {
      token: owner.token,
      expect: [201],
      body: { boardId: boardB.id, title: `我的 B1 ${suffix()}`, content: 'B1', contentFormat: 'PLAIN' },
    })
    created.push(first.id, second.id)

    const mine = await api('GET', '/users/me/posts?size=20', { token: owner.token })
    const ids = (mine.items ?? []).map((item) => item.id)
    assert(ids.includes(first.id) && ids.includes(second.id), '我的帖子应包含自己刚发的两条')
    // PostResponse 的字段是扁平的 authorId（不是嵌套 author 对象）
    assert(
      (mine.items ?? []).every((item) => item.authorId === owner.id),
      `我的帖子不应包含别人的帖子，实际作者 ${[...new Set((mine.items ?? []).map((item) => item.authorId))].join(',')}`,
    )
    assert(mine.total >= 2, `total 应至少为 2，实际 ${mine.total}`)
    // 倒序：最新发的排在前面
    assert(ids.indexOf(second.id) < ids.indexOf(first.id), '应按时间倒序（B1 比 A1 新）')

    const filtered = await api('GET', `/users/me/posts?channelId=${channelA.id}&size=20`, { token: owner.token })
    const filteredIds = (filtered.items ?? []).map((item) => item.id)
    assert(filteredIds.includes(first.id), '按 channelA 过滤应包含 A1')
    assert(!filteredIds.includes(second.id), '按 channelA 过滤不应包含 B1')
    assert(
      (filtered.items ?? []).every((item) => item.channelId === channelA.id),
      '过滤结果应全部属于 channelA',
    )
  })

  // ------------------------------------------------------------------
  // 二、助手多会话
  // ------------------------------------------------------------------
  await step('准备：临时文本模型（mock-chat，非视觉、非工具）', async () => {
    textModel = await api('POST', '/admin/ai/models', {
      token: admin.token,
      expect: [201],
      body: {
        name: `E2E UA Text ${suffix()}`,
        baseUrl: `${MOCK_URL}/v1`,
        apiKey: '',
        model: 'mock-chat',
        visionEnabled: false,
        reasoningEnabled: false,
        thinkingMode: 'NONE',
        isDefault: false,
        enabled: true,
        toolCallEnabled: false,
        timeoutSeconds: 30,
        maxTokens: 512,
      },
    })
    cleanup.modelIds.push(textModel.id)
  })

  await step('助手多会话：同一频道可建多个会话，消息互不串', async () => {
    const first = await api('POST', '/ai/conversations', {
      token: member.token,
      expect: [201],
      body: { scope: 'CHANNEL_ASSISTANT', channelId: channelA.id, title: `会话一 ${suffix()}`, modelConfigId: textModel.id },
    })
    const second = await api('POST', '/ai/conversations', {
      token: member.token,
      expect: [201],
      body: { scope: 'CHANNEL_ASSISTANT', channelId: channelA.id, title: `会话二 ${suffix()}`, modelConfigId: textModel.id },
    })
    cleanup.conversationIds.push(first.id, second.id)
    assert(first.id !== second.id, '应能建出两个不同会话')
    assert(first.scope === 'CHANNEL_ASSISTANT', `scope 应为 CHANNEL_ASSISTANT，实际 ${first.scope}`)

    const markerOne = `只属于会话一${suffix()}`
    const markerTwo = `只属于会话二${suffix()}`
    await askStream(member.token, first.id, markerOne)
    await askStream(member.token, second.id, markerTwo)

    const messagesOne = await api('GET', `/ai/conversations/${first.id}/messages?limit=20`, { token: member.token })
    const messagesTwo = await api('GET', `/ai/conversations/${second.id}/messages?limit=20`, { token: member.token })
    const textOne = JSON.stringify(messagesOne.items ?? [])
    const textTwo = JSON.stringify(messagesTwo.items ?? [])

    assert(textOne.includes(markerOne), '会话一应包含自己的提问')
    assert(!textOne.includes(markerTwo), '会话一不应出现会话二的内容')
    assert(textTwo.includes(markerTwo), '会话二应包含自己的提问')
    assert(!textTwo.includes(markerOne), '会话二不应出现会话一的内容')

    const list = await api('GET', `/ai/conversations?scope=CHANNEL_ASSISTANT&channelId=${channelA.id}&limit=30`, {
      token: member.token,
    })
    const ids = (list.items ?? []).map((item) => item.id)
    assert(ids.includes(first.id) && ids.includes(second.id), '会话列表应同时包含两个会话')
  })

  await step('助手多会话：非成员不能创建（403）', async () => {
    await api('POST', '/ai/conversations', {
      token: outsider.token,
      expect: [403],
      body: { scope: 'CHANNEL_ASSISTANT', channelId: channelA.id, title: '越权会话' },
    })
  })

  // ------------------------------------------------------------------
  // 三、视觉模型兼容
  // ------------------------------------------------------------------
  await step('视觉兼容：无视觉模型 + 带图提问不报错，且提示已忽略图片', async () => {
    const form = new FormData()
    form.append('file', new Blob([TINY_PNG], { type: 'image/png' }), 'pixel.png')
    form.append('type', 'image')
    const uploadResponse = await fetch(`${BASE_URL}/media/upload`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${owner.token}`, Accept: 'application/json' },
      body: form,
    })
    assert(uploadResponse.status === 201, `图片上传应 201，实际 ${uploadResponse.status}`)
    const uploaded = await uploadResponse.json()

    const conversation = await api('POST', '/ai/conversations', {
      token: owner.token,
      expect: [201],
      body: { scope: 'GLOBAL', title: `视觉兼容 ${suffix()}`, modelConfigId: textModel.id },
    })
    cleanup.conversationIds.push(conversation.id)

    await resetMockRequests()
    // 第一轮就带图：模型没有视觉能力，服务端应剥离图片而不是把 400 抛给用户
    const firstEvents = await askStream(owner.token, conversation.id, '这张图里有什么？', [uploaded.url])
    const firstNames = firstEvents.map((item) => item.event)
    assert(!firstNames.includes('error'), `带图提问不应报错，实际 ${JSON.stringify(firstEvents.find((e) => e.event === 'error')?.data)}`)
    const firstContent = firstEvents
      .filter((item) => item.event === 'delta')
      .map((item) => (typeof item.data === 'string' ? item.data : item.data?.content ?? ''))
      .join('')
    assert(firstContent.length > 0, '带图提问应有正常回答')

    const requests = await mockRequests()
    assert(requests.length > 0, 'mock 应收到请求')
    assert(
      requests[0].ignoredImagesNote === true,
      `服务端应追加「已忽略 N 张图片」的系统提示，实际 systemText=${requests[0].systemText.slice(0, 120)}`,
    )

    // 第二轮：历史里已经带图（上一条用户消息），继续提问也不能 400
    const secondEvents = await askStream(owner.token, conversation.id, '那再总结一下要点')
    const secondNames = secondEvents.map((item) => item.event)
    assert(!secondNames.includes('error'), '带图历史继续提问不应报错')
    const secondContent = secondEvents
      .filter((item) => item.event === 'delta')
      .map((item) => (typeof item.data === 'string' ? item.data : item.data?.content ?? ''))
      .join('')
    assert(secondContent.length > 0, '带图历史的追问也应有回答')
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
