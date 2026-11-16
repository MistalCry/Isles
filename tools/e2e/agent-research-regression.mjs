#!/usr/bin/env node
/**
 * AI 代理「两阶段 tick」与新契约回归（第 18 轮）。
 *
 * 覆盖：
 *   A. 代理资料同步：创建时带 avatarUrl → users.avatar_url / users.nickname 同步，
 *      GET /users/{id} 与 GET /users/{id}/card 都能拿到（前台用户卡片据此显示头像）。
 *   B. researchFirst=true：一次 run-now 的 tool_calls_json 必须有 research.web_search，
 *      且结构化 count 与 results.length 一致、results 含 title/url/engine/snippet；
 *      产出提示词里真的注入了素材（mock 侧 materialUrls），帖子正文与素材同源。
 *   C. researchFirst=false：退回单轮产出（tool_calls_json.research 为空、只 1 次模型调用）。
 *   D. 禁止编造 postId：comment 指向不存在的 postId → GUARDED「postId 不在本轮可见列表内」，且不写库。
 *
 * 前置：
 *   - mock-openai：`node tools/mock-openai/server.mjs 1235`（/debug/agent-plan 支持 plans/research/compose 三个队列）
 *   - SearXNG：默认 http://127.0.0.1:8888（`SEARX_URL` 可覆盖），researchFirst 用例会真的联网搜一次
 *   - 平台超管：ADMIN_USERNAME / ADMIN_PASSWORD
 *   - 本机 mysql 客户端（校验 users.avatar_url / 评论未落库；`E2E_DB_*` 可覆盖）
 *
 * 运行：BASE_URL=http://127.0.0.1:8082/api/v1 MOCK_URL=http://127.0.0.1:1235 \
 *         node tools/e2e/agent-research-regression.mjs
 */

import { execFileSync } from 'node:child_process'

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1'
const MOCK_URL = process.env.MOCK_URL || 'http://127.0.0.1:1235'
const SEARX_URL = process.env.SEARX_URL || 'http://127.0.0.1:8888'
const ADMIN_USERNAME = process.env.ADMIN_USERNAME || 'admin_demo'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Passw0rd123'

const CONFIG_KEYS = [
  'agent.enabled',
  'agent.twoPhaseEnabled',
  'agent.researchFirst',
  'agent.search.provider',
  'agent.search.baseUrl',
  'agent.search.dailyQuota',
  'agent.search.timeoutSeconds',
]

/** 代理头像（用仓库里真实存在的默认头像，避免前台出现坏图） */
const AGENT_AVATAR = '/uploads/default-avatar.png'

const results = []
const cleanup = {
  agentIds: [],
  channelId: null,
  ownerToken: null,
  adminToken: null,
  modelId: null,
  restoreDefaultModels: [],
  configs: new Map(),
}

class HttpError extends Error {
  constructor(status, body, label) {
    super(`${status} ${label}: ${typeof body === 'string' ? body : JSON.stringify(body)}`)
    this.status = status
    this.body = body
  }
}

class SkipCase extends Error {}

function skip(name, reason) {
  results.push({ name, status: 'skip', note: reason })
  console.log(`  ⊘ ${name}\n    跳过：${reason}`)
}

async function step(name, fn) {
  try {
    await fn()
    results.push({ name, status: 'pass' })
    console.log(`  ✓ ${name}`)
  } catch (error) {
    if (error instanceof SkipCase) {
      skip(name, error.message)
      return
    }
    results.push({ name, status: 'fail', error: error.message })
    console.error(`  ✗ ${name}\n    ${error.message}`)
  }
}

function assert(condition, message) {
  if (!condition) throw new Error(`断言失败：${message}`)
}

function suffix() {
  return `${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

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

// ---------------------------------------------------------------------------
// 数据库
// ---------------------------------------------------------------------------

const DB = {
  host: process.env.E2E_DB_HOST || '127.0.0.1',
  port: process.env.E2E_DB_PORT || '3306',
  name: process.env.E2E_DB_NAME || 'my_social_channel',
  user: process.env.E2E_DB_USERNAME || 'isles_app',
  password: process.env.E2E_DB_PASSWORD || 'IslesDev2026a7',
}

function dbExec(sql) {
  try {
    return execFileSync(
      'mysql',
      [
        '-h', DB.host, '-P', DB.port, '-u', DB.user, `-p${DB.password}`,
        '--default-character-set=utf8mb4', DB.name, '-N', '-B', '-e', sql,
      ],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
    )
  } catch (error) {
    throw new SkipCase(
      `无法连接数据库校验（缺 mysql 客户端或凭据不对）：${String(error.message).split('\n')[0]}`,
    )
  }
}

function dbValue(sql) {
  const rows = dbExec(sql)
    .trim()
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
  return rows[0] ?? ''
}

// ---------------------------------------------------------------------------
// 配置
// ---------------------------------------------------------------------------

async function snapshotConfigs(token) {
  const view = await api('GET', '/admin/config', { token })
  const map = new Map()
  for (const group of view.groups ?? []) {
    for (const item of group.items ?? []) map.set(item.key, item)
  }
  for (const key of CONFIG_KEYS) {
    const item = map.get(key)
    assert(item, `配置项缺失：${key}`)
    cleanup.configs.set(key, { value: item.value, overridden: item.overridden === true })
  }
}

async function setConfig(token, key, value) {
  return api('PUT', `/admin/config/${key}`, { token, body: { value: String(value) } })
}

async function restoreConfigs() {
  for (const [key, original] of cleanup.configs) {
    try {
      if (original.overridden) {
        await api('PUT', `/admin/config/${key}`, { token: cleanup.adminToken, body: { value: original.value } })
      } else {
        await api('POST', `/admin/config/${key}/reset`, { token: cleanup.adminToken })
      }
    } catch (error) {
      console.error(`  ! 还原配置失败 ${key}：${error.message}`)
    }
  }
  console.log('  ✓ 平台配置已按原状还原')
}

// ---------------------------------------------------------------------------
// 模型 / 代理
// ---------------------------------------------------------------------------

function modelBody(overrides = {}) {
  return {
    name: `E2E Mock Research ${suffix()}`,
    baseUrl: `${MOCK_URL}/v1`,
    apiKey: '',
    model: 'mock-chat',
    visionEnabled: false,
    reasoningEnabled: false,
    thinkingMode: 'NONE',
    isDefault: true,
    enabled: true,
    toolCallEnabled: false,
    timeoutSeconds: 30,
    maxTokens: 512,
    ...overrides,
  }
}

function toModelRequest(model, overrides = {}) {
  return {
    name: model.name,
    baseUrl: model.baseUrl,
    apiKey: '',
    model: model.model,
    visionEnabled: model.visionEnabled,
    reasoningEnabled: model.reasoningEnabled,
    thinkingMode: model.thinkingMode || 'NONE',
    isDefault: model.isDefault,
    enabled: model.enabled,
    toolCallEnabled: model.toolCallEnabled,
    timeoutSeconds: model.timeoutSeconds ?? 30,
    maxTokens: model.maxTokens ?? 2048,
    ...overrides,
  }
}

async function newUser(prefix) {
  const username = `${prefix}_${suffix()}`
  const password = 'Passw0rd123'
  await api('POST', '/auth/register', { expect: [201], body: { username, password, nickname: prefix } })
  const login = await api('POST', '/auth/login', { body: { username, password } })
  return { username, id: login.user.id, token: login.accessToken }
}

function agentBody(overrides = {}) {
  return {
    displayName: `研究代理${suffix()}`.slice(0, 20),
    bio: 'E2E 两阶段代理',
    interests: ['我的世界', '海岛'],
    tone: 'casual',
    activeHours: [],
    postsPerDay: 3,
    commentsPerDay: 2,
    channels: [],
    toolsEnabled: [],
    status: 'ACTIVE',
    avatarUrl: AGENT_AVATAR,
    ...overrides,
  }
}

async function queuePlans({ plans = [], research = [], compose = [] }) {
  return api('POST', '/debug/agent-plan', { base: MOCK_URL, body: { plans, research, compose } })
}

async function clearPlans() {
  await api('DELETE', '/debug/agent-plan', { base: MOCK_URL }).catch(() => null)
}

async function mockRequests() {
  const data = await api('GET', '/debug/requests', { base: MOCK_URL })
  return data.items ?? []
}

async function clearMockRequests() {
  await api('DELETE', '/debug/requests', { base: MOCK_URL }).catch(() => null)
}

async function listRuns(token, agentId, size = 20) {
  return api('GET', `/admin/agents/${agentId}/runs?page=1&size=${size}`, { token })
}

/** 解析 tool_calls_json：两阶段是 {research:[...],compose:{...}}，单轮是扁平对象 */
function parseTrace(run) {
  const raw = run?.toolCallsJson
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

/** 把发帖时间回拨，绕开「发帖间隔 ≥2 小时」闸门 */
function shiftPostRunsBack(agentId, hours = 3) {
  dbExec(
    `UPDATE agent_runs SET started_at = DATE_SUB(started_at, INTERVAL ${hours} HOUR)
     WHERE agent_id='${agentId}' AND output_type='POST' AND status='SUCCESS'`,
  )
}

// ---------------------------------------------------------------------------
// 清理
// ---------------------------------------------------------------------------

async function runCleanup() {
  console.log('\n清理：')
  await clearPlans()

  if (cleanup.configs.size > 0 && cleanup.adminToken) {
    await restoreConfigs()
  }

  for (const agentId of cleanup.agentIds) {
    try {
      await api('DELETE', `/admin/agents/${agentId}`, { token: cleanup.adminToken, expect: [204, 404] })
      console.log(`  ✓ 代理已删除 ${agentId}`)
    } catch (error) {
      console.error(`  ! 删除代理失败 ${agentId}：${error.message}`)
    }
    try {
      dbExec(`DELETE FROM agent_runs WHERE agent_id='${agentId}'`)
      dbExec(`DELETE FROM agent_memories WHERE agent_id='${agentId}'`)
      console.log(`  ✓ 代理运行日志/记忆已清理 ${agentId}`)
    } catch (error) {
      console.error(`  ! 清理代理数据失败 ${agentId}：${error.message}`)
    }
  }

  if (cleanup.modelId) {
    try {
      await api('DELETE', `/admin/ai/models/${cleanup.modelId}`, {
        token: cleanup.adminToken,
        expect: [204, 404],
      })
      console.log('  ✓ 临时模型配置已删除')
    } catch (error) {
      console.error(`  ! 删除临时模型配置失败：${error.message}`)
    }
  }
  for (const item of cleanup.restoreDefaultModels) {
    try {
      await api('PUT', `/admin/ai/models/${item.id}`, {
        token: cleanup.adminToken,
        body: { ...item.request, isDefault: true },
      })
      console.log(`  ✓ 原默认模型标记已还原 ${item.request.name}`)
    } catch (error) {
      console.error(`  ! 还原默认模型失败 ${item.id}：${error.message}`)
    }
  }

  if (cleanup.channelId && cleanup.ownerToken) {
    try {
      await api('POST', `/channels/${cleanup.channelId}/dissolve`, {
        token: cleanup.ownerToken,
        expect: [204, 200, 404],
      })
      console.log('  ✓ 测试频道已解散')
    } catch (error) {
      console.error(`  ! 解散频道失败：${error.message}`)
    }
  }
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------

async function main() {
  console.log(`\nAI 代理两阶段回归 · ${BASE_URL} · mock=${MOCK_URL} · searxng=${SEARX_URL}`)

  const login = await api('POST', '/auth/login', {
    body: { username: ADMIN_USERNAME, password: ADMIN_PASSWORD },
  })
  const admin = { token: login.accessToken, id: login.user.id }
  cleanup.adminToken = admin.token

  const me = await api('GET', '/admin/me', { token: admin.token })
  assert(me.admin === true, '需要平台管理员账号（ADMIN_USERNAME / ADMIN_PASSWORD）')

  let owner
  let channel
  let agent

  await step('准备：临时默认模型 + 频道 + 两阶段/强制研究开启', async () => {
    const models = await api('GET', '/admin/ai/models', { token: admin.token })
    cleanup.restoreDefaultModels = (models.items ?? [])
      .filter((item) => item.isDefault)
      .map((item) => ({ id: item.id, request: toModelRequest(item) }))
    const model = await api('POST', '/admin/ai/models', {
      token: admin.token,
      expect: [201],
      body: modelBody(),
    })
    cleanup.modelId = model.id

    await snapshotConfigs(admin.token)
    await setConfig(admin.token, 'agent.enabled', 'true')
    await setConfig(admin.token, 'agent.twoPhaseEnabled', 'true')
    await setConfig(admin.token, 'agent.researchFirst', 'true')
    await setConfig(admin.token, 'agent.search.provider', 'SEARXNG')
    await setConfig(admin.token, 'agent.search.baseUrl', SEARX_URL)
    await setConfig(admin.token, 'agent.search.dailyQuota', '200')
    await setConfig(admin.token, 'agent.search.timeoutSeconds', '10')

    owner = await newUser('rsown')
    cleanup.ownerToken = owner.token
    channel = await api('POST', '/channels', {
      token: owner.token,
      expect: [201],
      body: { name: `Research Audit ${suffix()}`, avatarUrl: '/uploads/default-avatar.png', type: 'PUBLIC' },
    })
    cleanup.channelId = channel.id

    const created = await api('POST', '/admin/agents', {
      token: admin.token,
      expect: [201],
      body: agentBody({ channels: [channel.id] }),
    })
    agent = { ...created, agentId: created.userId }
    cleanup.agentIds.push(agent.agentId)
    assert(agent.agentId, '创建代理应返回账号 id')
  })

  // ------------------------------------------------------------------
  // A. 代理资料同步
  // ------------------------------------------------------------------
  await step('资料同步：创建带 avatarUrl → 账号昵称/头像同步，公开资料与用户卡片都能拿到', async () => {
    assert(agent.avatarUrl === AGENT_AVATAR, `创建响应应回显 avatarUrl，实际 ${agent.avatarUrl}`)
    assert(agent.nickname === agent.displayName, `昵称应与展示名一致，实际 ${agent.nickname}`)

    const dbAvatar = dbValue(`SELECT avatar_url FROM users WHERE id='${agent.agentId}'`)
    const dbNickname = dbValue(`SELECT nickname FROM users WHERE id='${agent.agentId}'`)
    assert(dbAvatar === AGENT_AVATAR, `users.avatar_url 应同步，实际 ${dbAvatar}`)
    assert(dbNickname === agent.displayName, `users.nickname 应同步，实际 ${dbNickname}`)

    const publicUser = await api('GET', `/users/${agent.agentId}`)
    assert(publicUser.avatarUrl === AGENT_AVATAR, `GET /users/{id} 应返回头像，实际 ${publicUser.avatarUrl}`)
    assert(publicUser.nickname === agent.displayName, 'GET /users/{id} 应返回同步后的昵称')
    assert(publicUser.isAgent === true, 'GET /users/{id} 应标记 isAgent')

    const card = await api('GET', `/users/${agent.agentId}/card?channelId=${channel.id}`)
    assert(card.avatarUrl === AGENT_AVATAR, `用户卡片应返回头像（前台据此渲染），实际 ${card.avatarUrl}`)
    assert(card.nickname === agent.displayName, '用户卡片应返回同步后的昵称')
    assert(card.isAgent === true, '用户卡片应标记 isAgent')
  })

  // ------------------------------------------------------------------
  // B. researchFirst=true：必搜 + 素材相关
  // ------------------------------------------------------------------
  let researchRun
  let researchTrace

  await step('researchFirst=true：tool_calls_json 必有 web_search，结构化 results/count 一致', async () => {
    await clearMockRequests()
    await clearPlans()
    await queuePlans({
      research: [{ actions: [{ action: 'web_search', args: { query: '我的世界', limit: 5 } }] }],
      compose: [
        {
          action: 'create_post',
          args: {
            channelId: channel.id,
            title: `素材帖 ${suffix()}`,
            content:
              '刚查到 {{materialCount}} 条资料，第一条是《{{materialTitle}}》（{{materialUrl}}）。' +
              '这条帖子只能基于这些素材写，用来验证两阶段链路把检索结果真的交给了产出阶段。',
          },
        },
      ],
    })

    const res = await api('POST', `/admin/agents/${agent.agentId}/run-now`, { token: admin.token })
    assert(res.status === 'SUCCESS', `run-now 应成功，实际 ${res.status} ${res.reason ?? ''}`)
    assert(res.run?.outputType === 'POST', `产出应为 POST，实际 ${res.run?.outputType}`)
    researchRun = res.run
    researchTrace = parseTrace(res.run)

    assert(Array.isArray(researchTrace?.research), '两阶段轨迹应有 research 数组')
    const searchStep = researchTrace.research.find((item) => item.action === 'web_search')
    assert(searchStep, `research 里必须有 web_search，实际 ${JSON.stringify(researchTrace.research.map((s) => s.action))}`)

    assert(typeof searchStep.count === 'number', `结构化字段 count 应存在，实际 ${searchStep.count}`)
    assert(Array.isArray(searchStep.results), '结构化字段 results 应存在')
    assert(
      searchStep.count === searchStep.results.length,
      `count 应与 results.length 一致：${searchStep.count} vs ${searchStep.results.length}`,
    )
    assert(searchStep.count >= 1, `web_search 应有结果，实际 ${searchStep.count}`)
    const first = searchStep.results[0]
    for (const field of ['title', 'url', 'engine', 'snippet']) {
      assert(first[field] !== undefined, `results[0] 应含 ${field}，实际 ${JSON.stringify(first).slice(0, 120)}`)
    }
    assert(searchStep.query === '我的世界', `结构化 query 应回显查询词，实际 ${searchStep.query}`)
    assert(
      researchTrace.compose?.action === 'create_post',
      `compose 应是发帖，实际 ${researchTrace.compose?.action}`,
    )
  })

  await step('researchFirst=true：素材真的注入了产出提示词，帖子正文与素材同源', async () => {
    const requests = await mockRequests()
    const composeReq = [...requests].reverse().find((item) => item.agentPhase === 'compose')
    assert(composeReq, 'mock 应收到产出阶段请求')
    assert(composeReq.hasMaterials === true, '产出提示词应包含「今日素材」段')
    assert(composeReq.materialCount >= 1, `素材条数应 ≥1，实际 ${composeReq.materialCount}`)

    const searchStep = researchTrace.research.find((item) => item.action === 'web_search')
    const firstResult = searchStep.results[0]
    assert(
      composeReq.materialUrls.includes(firstResult.url),
      `素材段应包含检索到的 url：期望 ${firstResult.url}，实际 ${composeReq.materialUrls.slice(0, 3).join(',')}`,
    )

    // 帖子正文里的标题/链接来自素材（mock 用占位符从提示词里取值再产出）
    const post = await api('GET', `/posts/${researchRun.outputId}`)
    assert(post.authorId === agent.agentId, '帖子作者应是代理')
    assert(
      post.content.includes(firstResult.title),
      `正文应引用素材标题「${firstResult.title}」，实际「${post.content.slice(0, 160)}」`,
    )
    if (firstResult.url) {
      assert(post.content.includes(firstResult.url), `正文应引用素材链接 ${firstResult.url}`)
    }
  })

  // ------------------------------------------------------------------
  // C. researchFirst=false：退回单轮
  // ------------------------------------------------------------------
  await step('researchFirst=false：退回单轮产出（无 web_search、只 1 次模型调用）', async () => {
    await setConfig(admin.token, 'agent.researchFirst', 'false')
    shiftPostRunsBack(agent.agentId)
    await clearMockRequests()
    await clearPlans()
    await queuePlans({
      compose: [
        {
          action: 'create_post',
          args: {
            channelId: channel.id,
            title: `单轮帖 ${suffix()}`,
            content: '关掉强制研究后，代理应能直接产出，不必先联网搜索。',
          },
        },
      ],
    })

    const res = await api('POST', `/admin/agents/${agent.agentId}/run-now`, { token: admin.token })
    assert(res.status === 'SUCCESS', `单轮应成功，实际 ${res.status} ${res.reason ?? ''}`)
    assert(res.run?.outputType === 'POST', `产出应为 POST，实际 ${res.run?.outputType}`)

    const trace = parseTrace(res.run)
    const researchSteps = Array.isArray(trace?.research) ? trace.research : []
    assert(researchSteps.length === 0, `关掉强制研究后 research 应为空，实际 ${researchSteps.length} 步`)
    assert(
      !JSON.stringify(trace).includes('"web_search"'),
      '关掉强制研究后不应出现 web_search',
    )

    const requests = await mockRequests()
    assert(requests.length === 1, `单轮应只调用 1 次模型，实际 ${requests.length} 次`)
    assert(requests[0].agentPhase === 'compose', '单轮调用应直接进入产出阶段')
    assert(requests[0].materialCount === 0, `无研究时素材应为空，实际 ${requests[0].materialCount}`)
  })

  // ------------------------------------------------------------------
  // D. 禁止编造 postId
  // ------------------------------------------------------------------
  await step('禁止编造 postId：comment 指向不存在的帖子 → GUARDED 且不写库', async () => {
    await setConfig(admin.token, 'agent.researchFirst', 'true')
    const fakePostId = '01NOTEXIST00000000000000'
    const before = Number(dbValue(`SELECT COUNT(*) FROM comments WHERE author_id='${agent.agentId}'`))

    await clearPlans()
    await queuePlans({
      // 研究用站内列表：可见 id 非空，说明拒绝不是因为「列表为空」
      research: [{ actions: [{ action: 'list_channel_posts', args: { channelId: channel.id, limit: 5 } }] }],
      compose: [
        {
          action: 'comment',
          args: { postId: fakePostId, content: '这条评论的目标是编造的 postId，不应该落库。' },
        },
      ],
    })

    const res = await api('POST', `/admin/agents/${agent.agentId}/run-now`, { token: admin.token })
    assert(res.status === 'GUARDED', `编造 postId 应被闸门拦下，实际 ${res.status} ${res.reason ?? ''}`)
    assert(
      (res.reason ?? '').includes('postId 不在本轮可见列表内'),
      `理由应说明 postId 不在可见列表，实际「${res.reason}」`,
    )

    const after = Number(dbValue(`SELECT COUNT(*) FROM comments WHERE author_id='${agent.agentId}'`))
    assert(after === before, `不应写入评论：${before} → ${after}`)

    // 运行日志也要能回放这次被拒（compose 段在、outputId 为空）
    // 注意：成功路径的 compose 是对象，被闸门拦下时是「模型原文」字符串（见缺陷 D1），
    // 这里两种形态都接受，只断言「能读出被拒的 comment 计划」。
    const trace = parseTrace(res.run)
    const composeRaw = trace?.compose
    const composePlan =
      typeof composeRaw === 'string'
        ? (() => {
            try {
              return JSON.parse(composeRaw)
            } catch {
              return null
            }
          })()
        : composeRaw
    assert(
      composePlan?.action === 'comment',
      `日志应记录被拒的 comment 计划，实际 compose=${JSON.stringify(composeRaw)?.slice(0, 200)}`,
    )
    assert(
      composePlan?.args?.postId === fakePostId,
      `日志里应保留被拒的 postId，实际 ${composePlan?.args?.postId}`,
    )
    assert(!res.run?.outputId, '被拒的运行不应有产出 id')
  })

  const passed = results.filter((item) => item.status === 'pass').length
  const failed = results.filter((item) => item.status === 'fail').length
  const skipped = results.filter((item) => item.status === 'skip').length
  console.log(`\n结果：${passed} 通过 / ${failed} 失败 / ${skipped} 跳过（共 ${results.length} 项）\n`)
  if (failed > 0) {
    console.log('失败项：')
    results
      .filter((item) => item.status === 'fail')
      .forEach((item) => console.log(`  - ${item.name}\n    ${item.error}`))
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
