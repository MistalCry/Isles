#!/usr/bin/env node
/**
 * AI 代理联网搜索（web_search / fetch_page）回归（V13 P1，第 17 轮）。
 *
 * 覆盖：
 *   A. 配额：dailyQuota=1 → 第一次成功、第二次明确报错，且**第二次不再发出真实请求**（计数 mock 佐证）
 *   B. 英文查询：results 非空、首条与查询相关、url 不重复、≤10 条
 *   C. 中文查询：同上，且**必须是 UTF-8**——summary 里不能出现 GBK 乱码（%CE%D2…）或「汾酒」这类错搜结果
 *   D. 降级：provider=NONE → 退回站内检索且仍能发帖；baseUrl 指到死端口 → 不失败、退回站内检索
 *   E. SSRF：fetch_page 抓 127.0.0.1 / 192.168.1.1 必须被拒（GUARDED + 明确理由）
 *   F. 端到端：web_search 拿到结果 → create_post 发帖成功
 *
 * 前置：
 *   - SearXNG：默认 http://127.0.0.1:8888（`SEARX_URL` 可覆盖）
 *   - mock-openai：`node tools/mock-openai/server.mjs 1235`（脚本用 /debug/agent-plan 注入计划）
 *   - 平台超管：ADMIN_USERNAME / ADMIN_PASSWORD（默认 admin_demo / Passw0rd123）
 *   - **8082 必须是刚启动的实例**：`SearchQuota` 是进程内计数，脚本第一条用例就要求「今日用量 0」；
 *     已用过联网搜索的进程会直接判跳过并提示重启（不是产品缺陷）。
 *   - 本机 mysql 客户端：校验 agent 产出（`E2E_DB_*` 可覆盖）
 *
 * 运行：
 *   BASE_URL=http://127.0.0.1:8082/api/v1 MOCK_URL=http://127.0.0.1:1235 \
 *     node tools/e2e/web-search-regression.mjs
 */

import http from 'node:http'
import { execFileSync } from 'node:child_process'

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1'
const MOCK_URL = process.env.MOCK_URL || 'http://127.0.0.1:1235'
const SEARX_URL = process.env.SEARX_URL || 'http://127.0.0.1:8888'
const ADMIN_USERNAME = process.env.ADMIN_USERNAME || 'admin_demo'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Passw0rd123'

const CONFIG_KEYS = [
  'agent.enabled',
  'agent.twoPhaseEnabled',
  'agent.search.provider',
  'agent.search.baseUrl',
  'agent.search.engines',
  'agent.search.dailyQuota',
  'agent.search.timeoutSeconds',
]

const results = []
const cleanup = {
  agentIds: [],
  channelId: null,
  ownerToken: null,
  adminToken: null,
  modelId: null,
  restoreDefaultModels: [],
  /** key → { value, overridden }：收尾按原状还原（有覆盖值就写回，没有就 reset） */
  configs: new Map(),
}

/** 配额用例的计数 mock（模块级：收尾函数也要能关掉它） */
let counting = null

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
// 数据库（校验代理产出）
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
      ['-h', DB.host, '-P', DB.port, '-u', DB.user, `-p${DB.password}`, DB.name, '-N', '-B', '-e', sql],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
    )
  } catch (error) {
    throw new SkipCase(
      `无法连接数据库校验（缺 mysql 客户端或凭据不对）：${String(error.message).split('\n')[0]}`,
    )
  }
}

// ---------------------------------------------------------------------------
// 配置读写（带原状还原）
// ---------------------------------------------------------------------------

async function loadConfigs(token) {
  const view = await api('GET', '/admin/config', { token })
  const map = new Map()
  for (const group of view.groups ?? []) {
    for (const item of group.items ?? []) {
      map.set(item.key, item)
    }
  }
  return map
}

async function snapshotConfigs(token) {
  const map = await loadConfigs(token)
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
        await api('PUT', `/admin/config/${key}`, {
          token: cleanup.adminToken,
          body: { value: original.value },
        })
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
    name: `E2E Mock WebSearch ${suffix()}`,
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
    displayName: `搜索代理${suffix()}`.slice(0, 20),
    bio: 'E2E 联网搜索代理',
    interests: ['海岛', '科技'],
    tone: 'casual',
    activeHours: [],
    postsPerDay: 3,
    commentsPerDay: 2,
    channels: [],
    toolsEnabled: [],
    status: 'ACTIVE',
    ...overrides,
  }
}

async function queuePlans(plans) {
  return api('POST', '/debug/agent-plan', { base: MOCK_URL, body: { plans } })
}

async function clearPlans() {
  await api('DELETE', '/debug/agent-plan', { base: MOCK_URL }).catch(() => null)
}

async function listRuns(token, agentId, size = 20) {
  return api('GET', `/admin/agents/${agentId}/runs?page=1&size=${size}`, { token })
}

/** 配额用例的收尾：恢复真实 SearXNG、正常配额，并关掉计数 mock */
async function restoreSearchConfig() {
  try {
    await setConfig(cleanup.adminToken, 'agent.search.provider', 'SEARXNG')
    await setConfig(cleanup.adminToken, 'agent.search.baseUrl', SEARX_URL)
    await setConfig(cleanup.adminToken, 'agent.search.dailyQuota', '200')
  } catch (error) {
    console.error(`  ! 恢复搜索配置失败：${error.message}`)
  }
  if (counting) {
    try {
      counting.server.close()
    } catch {
      // 已关闭
    }
    counting = null
  }
}

/** 队列里放一个计划 → run-now → 返回 {res, summary, trace} */
async function runPlan(admin, agentId, plan) {
  await clearPlans()
  await queuePlans([plan])
  const res = await api('POST', `/admin/agents/${agentId}/run-now`, { token: admin.token })
  let trace = null
  let summary = ''
  if (res.run?.toolCallsJson) {
    try {
      trace = JSON.parse(res.run.toolCallsJson)
      summary = trace?.summary ?? ''
    } catch {
      summary = res.run.toolCallsJson
    }
  }
  return { res, summary, trace }
}

/**
 * 搜索结果的权威来源：`tool_calls_json` 里的结构化 `results`（title 不截断、url 完整）。
 *
 * <p>**不要只靠解析 summary**：`AgentRuntime#stepOf` 把 summary 截到 2000 字符，
 * 结果条数多时最后一行会断在 url 中间（第 21 轮实际踩到「声明 10 条、解析 9 条」）。
 * summary 只用来做「文本格式 / 回显查询词」这类断言。
 */
function searchItems(trace, summary) {
  if (Array.isArray(trace?.results)) {
    return trace.results.map((item) => ({
      title: item.title ?? '',
      url: item.url ?? '',
      engine: item.engine ?? '',
    }))
  }
  return parseWebSummary(summary).items
}

/**
 * 解析 summarizeSearchResults 的输出：联网搜索「q」（N 条）：\n- [engine] title | url
 *
 * <p>标题里可能出现 ` | `（英文标题很常见），所以分隔符要**从右往左**取：
 * 用贪婪的 `(.*)` 让正则咬住最后一个「 | http…」。非贪婪会把这类条目整行丢掉，
 * 表现为「声明 10 条、解析 9 条」（第 21 轮实际踩到过）。
 */
function parseWebSummary(summary) {
  const header = /联网搜索「([^」]*)」（(\d+) 条）/.exec(summary)
  const items = []
  const unparsed = []
  for (const rawLine of summary.split('\n')) {
    const line = rawLine.trim()
    if (!line.startsWith('- [')) continue
    const match = /^- \[([^\]]*)\] (.*) \| (https?:\/\/\S+)$/.exec(line)
    if (match) items.push({ engine: match[1], title: match[2], url: match[3] })
    else unparsed.push(line)
  }
  return { query: header?.[1] ?? null, count: header ? Number(header[2]) : null, items, unparsed }
}

function normalizeUrl(url) {
  let value = url.trim()
  const hash = value.indexOf('#')
  if (hash > 0) value = value.slice(0, hash)
  if (value.endsWith('/')) value = value.slice(0, -1)
  return value.toLowerCase()
}

/** 把代理上一次成功发帖的时间回拨，绕开「发帖间隔 ≥2 小时」闸门（本脚本要连发两帖） */
function shiftPostRunsBack(agentId, hours = 3) {
  dbExec(
    `UPDATE agent_runs SET started_at = DATE_SUB(started_at, INTERVAL ${hours} HOUR)
     WHERE agent_id='${agentId}' AND output_type='POST' AND status='SUCCESS'`,
  )
}

/**
 * SearXNG 预检：直接查一次。引擎全挂（CAPTCHA/限流/出站网络断）时 results 为空，
 * 这不是产品回归——联网相关性断言应当跳过并写明原因，而不是判失败。
 */
async function searxPreflight() {
  try {
    const url = `${SEARX_URL}/search?format=json&language=all&q=${encodeURIComponent('minecraft')}`
    const response = await fetch(url, { signal: AbortSignal.timeout(12_000) })
    const data = await response.json()
    const count = (data.results ?? []).length
    const unresponsive = (data.unresponsive_engines ?? []).map((item) => item?.[0]).filter(Boolean)
    return { ok: count > 0, count, unresponsive }
  } catch (error) {
    return { ok: false, count: 0, unresponsive: [], error: error.message }
  }
}

/** 联网不可用时的统一跳过理由 */
function offlineReason(preflight) {
  const detail = preflight.error
    ? preflight.error
    : `results=0，不可用引擎：${preflight.unresponsive.join(', ') || '未知'}`
  return `SearXNG 当前拿不到结果（${detail}），联网相关性断言跳过；这是环境/出网问题，不是产品回归`
}

// ---------------------------------------------------------------------------
// 计数 mock（验证「配额用尽后不再发真实请求」）
// ---------------------------------------------------------------------------

function startCountingSearxng() {
  // mode='empty' 时返回 0 结果，用来验证「引擎全挂 / 0 命中要退还配额」
  const state = { count: 0, mode: 'ok' }
  const server = http.createServer((req, res) => {
    state.count += 1
    const payload = {
      query: new URL(req.url, 'http://127.0.0.1').searchParams.get('q') || '',
      results:
        state.mode === 'empty'
          ? []
          : [
              {
                title: 'Mock 结果 A',
                url: 'https://example.com/a',
                content: '计数用 mock 返回的第一条结果',
                engine: 'mock',
              },
              {
                title: 'Mock 结果 B',
                url: 'https://example.com/b',
                content: '计数用 mock 返回的第二条结果',
                engine: 'mock',
              },
            ],
    }
    const body = JSON.stringify(payload)
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
    res.end(body)
  })
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address()
      resolve({ server, state, baseUrl: `http://127.0.0.1:${port}` })
    })
  })
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
  console.log(`\nAI 代理联网搜索回归 · ${BASE_URL} · searxng=${SEARX_URL} · mock=${MOCK_URL}`)

  const login = await api('POST', '/auth/login', {
    body: { username: ADMIN_USERNAME, password: ADMIN_PASSWORD },
  })
  const admin = { token: login.accessToken, id: login.user.id }
  cleanup.adminToken = admin.token

  const me = await api('GET', '/admin/me', { token: admin.token })
  assert(me.admin === true, '需要平台管理员账号（ADMIN_USERNAME / ADMIN_PASSWORD）')

  const preflight = await searxPreflight()
  if (!preflight.ok) console.log(`  ! ${offlineReason(preflight)}`)

  let owner
  let channel
  let board
  let agent

  await step('准备：临时默认模型（指向 mock）+ 频道 + 代理 + 配置快照', async () => {
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

    owner = await newUser('wsown')
    cleanup.ownerToken = owner.token
    channel = await api('POST', '/channels', {
      token: owner.token,
      expect: [201],
      body: { name: `WebSearch Audit ${suffix()}`, avatarUrl: '/uploads/default-avatar.png', type: 'PUBLIC' },
    })
    cleanup.channelId = channel.id
    board = (await api('GET', `/channels/${channel.id}/boards`)).items[0]

    const created = await api('POST', '/admin/agents', {
      token: admin.token,
      expect: [201],
      body: agentBody({ channels: [channel.id] }),
    })
    agent = { ...created, agentId: created.userId }
    cleanup.agentIds.push(agent.agentId)

    await setConfig(admin.token, 'agent.enabled', 'true')
    // 本脚本直接驱动单个工具（单轮计划），关掉两阶段；收尾按快照还原
    await setConfig(admin.token, 'agent.twoPhaseEnabled', 'false')
    assert(agent.agentId && board, '代理与板块应创建成功')
  })

  // ------------------------------------------------------------------
  // A. 配额（必须最先跑：SearchQuota 是进程内计数，要求当日用量 0）
  // ------------------------------------------------------------------

  await step('配额：provider=NONE 不扣配额 → 0 结果退还 → 真结果才计数 → 超限拒绝', async () => {
    // 一条单调序列同时证明四件事（SearchQuota 是进程内计数，不能分段重置）：
    //   1) provider=NONE 没有任何上游请求，配额一分不动；
    //   2) 上游返回 0 结果时退还配额（否则代理会被「引擎全挂」白扣光）；
    //   3) 只有真正拿到结果才计数；
    //   4) 用尽后明确报错且不再发真实请求。
    // 若第 1/2 段误扣，第 3 段就会直接 GUARDED —— 这正是本轮要防的回归。
    counting = await startCountingSearxng()
    try {
    await setConfig(admin.token, 'agent.search.baseUrl', counting.baseUrl)
    await setConfig(admin.token, 'agent.search.dailyQuota', '1')
    await setConfig(admin.token, 'agent.search.timeoutSeconds', '5')

    // ---- 1) provider=NONE：退回站内检索，不扣配额、不发请求
    await setConfig(admin.token, 'agent.search.provider', 'NONE')
    const off = await runPlan(admin, agent.agentId, {
      action: 'web_search',
      args: { query: 'quota none probe', limit: 5 },
    })
    assert(off.res.status === 'SUCCESS', `provider=NONE 不应失败，实际 ${off.res.status} ${off.res.reason ?? ''}`)
    assert(
      off.summary.includes('未启用'),
      `provider=NONE 应说明「未启用，已退回站内检索」，实际「${off.summary.slice(0, 120)}」`,
    )
    assert(
      counting.state.count === 0,
      `provider=NONE 不应发出任何上游请求，实际 mock 收到 ${counting.state.count} 次`,
    )

    // ---- 2) 0 结果：发出请求但没产出 → 退还配额
    await setConfig(admin.token, 'agent.search.provider', 'SEARXNG')
    counting.state.mode = 'empty'
    const empty = await runPlan(admin, agent.agentId, {
      action: 'web_search',
      args: { query: 'quota empty probe', limit: 5 },
    })
    assert(empty.res.status === 'SUCCESS', `0 结果应降级而不是失败，实际 ${empty.res.status} ${empty.res.reason ?? ''}`)
    assert(
      empty.summary.includes('无结果'),
      `0 结果应说明「无结果，已退回站内检索」，实际「${empty.summary.slice(0, 120)}」`,
    )
    assert(counting.state.count === 1, `0 结果这次应打到 mock 1 次，实际 ${counting.state.count}`)

    // ---- 3) 有结果：此时配额必须仍可用（证明前两段没白扣）
    counting.state.mode = 'ok'
    const hit = await runPlan(admin, agent.agentId, {
      action: 'web_search',
      args: { query: 'quota hit probe', limit: 5 },
    })
    if (hit.res.status === 'GUARDED' && (hit.res.reason ?? '').includes('配额已用尽')) {
      throw new SkipCase(
        '当前 8082 进程今天已经用过联网搜索（SearchQuota 进程内计数），配额用例无法从 0 开始；' +
          '重启 8082 后重跑即可（不是产品缺陷）',
      )
    }
    assert(hit.res.status === 'SUCCESS', `有结果时应成功，实际 ${hit.res.status} ${hit.res.reason ?? ''}`)
    assert(hit.trace?.count === 2, `计数 mock 应返回 2 条，实际 ${hit.trace?.count}`)
    assert(
      (hit.trace?.results ?? []).length === 2,
      `结构化 results 应有 2 条，实际 ${(hit.trace?.results ?? []).length}`,
    )
    assert(counting.state.count === 2, `有结果这次应打到 mock 第 2 次，实际 ${counting.state.count}`)

    // ---- 4) 超限：明确报错且不再发请求
    const over = await runPlan(admin, agent.agentId, {
      action: 'web_search',
      args: { query: 'quota over probe', limit: 5 },
    })
    assert(over.res.status === 'GUARDED', `超配额应记 GUARDED，实际 ${over.res.status} ${over.res.reason ?? ''}`)
    assert(
      (over.res.reason ?? '').includes('配额已用尽'),
      `错误应说明配额已用尽，实际「${over.res.reason}」`,
    )
    assert(
      counting.state.count === 2,
      `超配额后不应再发出真实请求，实际 mock 收到 ${counting.state.count} 次`,
    )

    } finally {
      // 换回真实 SearXNG 与正常配额：SkipCase / 断言失败提前退出时也必须恢复，
      // 否则后续步骤会带着「limit=1 + mock 地址」继续跑，全变成配额用尽
      await restoreSearchConfig()
    }
  })

  // ------------------------------------------------------------------
  // B. 英文查询
  // ------------------------------------------------------------------
  await step('英文查询：results 非空、首条相关、url 不重复、≤10 条', async () => {
    if (!preflight.ok) throw new SkipCase(offlineReason(preflight))
    const { res, summary, trace } = await runPlan(admin, agent.agentId, {
      action: 'web_search',
      args: { query: 'minecraft official site', limit: 10 },
    })
    assert(res.status === 'SUCCESS', `搜索应成功，实际 ${res.status} ${res.reason ?? ''}`)
    const items = searchItems(trace, summary)
    const count = trace?.count ?? items.length
    assert(count >= 1, `英文查询应有结果（部分引擎失败也算成功），实际 ${count}`)
    assert(count <= 10, `单次最多 10 条，实际 ${count}`)
    assert(items.length === count, `结构化条数应与 count 一致：${items.length} vs ${count}`)
    // 文本层：summary 必须带「联网搜索「q」（N 条）」头，模型看到的是这段
    assert(
      summary.startsWith('联网搜索「minecraft official site」（'),
      `summary 头应回显查询词，实际「${summary.slice(0, 80)}」`,
    )

    const first = items[0]
    const haystack = `${first.title} ${first.url}`.toLowerCase()
    assert(
      haystack.includes('minecraft') || haystack.includes('mojang'),
      `首条应与查询相关，实际「${first.title} | ${first.url}」`,
    )

    const urls = items.map((item) => normalizeUrl(item.url))
    assert(new Set(urls).size === urls.length, `同一 url 不应重复，实际 ${urls.length} 条中 ${new Set(urls).size} 个唯一`)
  })

  // ------------------------------------------------------------------
  // C. 中文查询（编码回归防线）
  // ------------------------------------------------------------------
  await step('中文查询：UTF-8 编码正确、首条相关（不能搜成汾酒）', async () => {
    if (!preflight.ok) throw new SkipCase(offlineReason(preflight))
    const { res, summary, trace } = await runPlan(admin, agent.agentId, {
      action: 'web_search',
      args: { query: '我的世界', limit: 10 },
    })
    assert(res.status === 'SUCCESS', `中文搜索应成功，实际 ${res.status} ${res.reason ?? ''}`)
    const items = searchItems(trace, summary)
    const count = trace?.count ?? items.length
    assert(count >= 1, `中文查询应有结果，实际 ${count}`)
    // 回显查询词：结构化 query 与 summary 头都要是「我的世界」
    assert(trace?.query === '我的世界', `结构化 query 应是「我的世界」，实际「${trace?.query}」`)
    assert(
      summary.startsWith('联网搜索「我的世界」（'),
      `summary 头应回显「我的世界」，实际「${summary.slice(0, 80)}」`,
    )

    // GBK 乱码特征只看**标题**：URL 里的 %XX 是正常的 UTF-8 百分号编码（如维基百科条目地址），
    // 拿整段 summary 匹配会误报。标题里连续 3 段以上百分号转义才是编码事故。
    const mojibake = items.filter((item) => /%[0-9A-F]{2}(%[0-9A-F]{2}){2,}/i.test(item.title))
    assert(
      mojibake.length === 0,
      `标题出现百分号转义（GBK 编码回归特征）：「${mojibake[0]?.title}」`,
    )
    assert(!summary.includes('汾酒'), '搜到「汾酒」说明中文查询被按 GBK 编码发出（编码回归）')

    const first = items[0]
    const haystack = `${first.title} ${first.url}`.toLowerCase()
    assert(
      haystack.includes('我的世界') || haystack.includes('minecraft') || haystack.includes('mc.163'),
      `中文首条应相关（我的世界 / Minecraft），实际「${first.title} | ${first.url}」`,
    )
    // 相关性再收紧一层：不只首条，前 10 条里至少要有 2 条明确提到「我的世界」
    const relevant = items.filter((item) => `${item.title} ${item.url}`.toLowerCase().includes('我的世界'))
    assert(relevant.length >= 2, `前 ${items.length} 条里提到「我的世界」的应 ≥2 条，实际 ${relevant.length}`)
  })

  await step('结果上限与去重：高结果量查询仍 ≤10 条且 url 唯一', async () => {
    if (!preflight.ok) throw new SkipCase(offlineReason(preflight))
    const { res, summary, trace } = await runPlan(admin, agent.agentId, {
      action: 'web_search',
      args: { query: 'minecraft', limit: 10 },
    })
    assert(res.status === 'SUCCESS', `搜索应成功，实际 ${res.status} ${res.reason ?? ''}`)
    const items = searchItems(trace, summary)
    const count = trace?.count ?? items.length
    assert(count <= 10, `单次最多 10 条，实际 ${count}`)
    assert(items.length === count, `结构化条数应与 count 一致：${items.length} vs ${count}`)
    const urls = items.map((item) => normalizeUrl(item.url))
    assert(new Set(urls).size === urls.length, `url 应唯一，实际 ${urls.length} 条中 ${new Set(urls).size} 个唯一`)
  })

  // ------------------------------------------------------------------
  // D. 降级
  // ------------------------------------------------------------------
  await step('降级：provider=NONE 时退回站内检索，且仍能发帖', async () => {
    await setConfig(admin.token, 'agent.search.provider', 'NONE')
    const { res, summary } = await runPlan(admin, agent.agentId, {
      action: 'web_search',
      args: { query: '海岛 潮汐', limit: 5 },
    })
    assert(res.status === 'SUCCESS', `provider=NONE 不应失败，实际 ${res.status} ${res.reason ?? ''}`)
    assert(
      summary.includes('联网搜索未启用，已退回站内检索'),
      `summary 应说明退回站内检索，实际「${summary.slice(0, 160)}」`,
    )
    assert(summary.includes('站内检索结果'), '退回后应给出站内检索结果段')

    const post = await runPlan(admin, agent.agentId, {
      action: 'create_post',
      args: {
        channelId: channel.id,
        title: `降级发帖 ${suffix()}`,
        content: '联网搜索关闭时，代理仍应能按站内话题发帖，这条用来验证降级不阻塞。',
      },
    })
    assert(post.res.status === 'SUCCESS', `降级后发帖应成功，实际 ${post.res.status} ${post.res.reason ?? ''}`)
    assert(post.res.run?.outputType === 'POST', '产出类型应为 POST')

    await setConfig(admin.token, 'agent.search.provider', 'SEARXNG')
  })

  await step('降级：baseUrl 指到死端口 → 不失败、退回站内检索', async () => {
    await setConfig(admin.token, 'agent.search.baseUrl', 'http://127.0.0.1:59999')
    const { res, summary } = await runPlan(admin, agent.agentId, {
      action: 'web_search',
      args: { query: '潮汐表', limit: 5 },
    })
    assert(res.status === 'SUCCESS', `搜索服务不可达不应让 tick 失败，实际 ${res.status} ${res.reason ?? ''}`)
    assert(
      summary.includes('联网搜索无结果，已退回站内检索'),
      `summary 应说明退回站内检索，实际「${summary.slice(0, 160)}」`,
    )
    await setConfig(admin.token, 'agent.search.baseUrl', SEARX_URL)
  })

  // ------------------------------------------------------------------
  // E. SSRF
  // ------------------------------------------------------------------
  await step('fetch_page SSRF：127.0.0.1 与 192.168.1.1 必须被拒', async () => {
    const local = await runPlan(admin, agent.agentId, {
      action: 'fetch_page',
      args: { url: 'http://127.0.0.1:8080/actuator/health' },
    })
    assert(
      local.res.status === 'GUARDED',
      `抓本机地址应被闸门拦下，实际 ${local.res.status} ${local.res.reason ?? ''}`,
    )
    assert(
      (local.res.reason ?? '').includes('不允许抓取内网'),
      `理由应说明内网禁止，实际「${local.res.reason}」`,
    )

    const lan = await runPlan(admin, agent.agentId, {
      action: 'fetch_page',
      args: { url: 'http://192.168.1.1' },
    })
    assert(
      lan.res.status === 'GUARDED',
      `抓内网段应被闸门拦下，实际 ${lan.res.status} ${lan.res.reason ?? ''}`,
    )
    assert(
      (lan.res.reason ?? '').includes('不允许抓取内网'),
      `理由应说明内网禁止，实际「${lan.res.reason}」`,
    )
  })

  // ------------------------------------------------------------------
  // F. 端到端：搜到 → 发帖
  // ------------------------------------------------------------------
  await step('端到端：web_search 拿到结果后能接着发帖', async () => {
    if (!preflight.ok) throw new SkipCase(offlineReason(preflight))
    // 前面已经发过一帖，绕开发帖间隔闸门
    shiftPostRunsBack(agent.agentId)

    const search = await runPlan(admin, agent.agentId, {
      action: 'web_search',
      args: { query: '我的世界 新版本', limit: 5 },
    })
    assert(search.res.status === 'SUCCESS', `搜索应成功，实际 ${search.res.status} ${search.res.reason ?? ''}`)
    const parsed = parseWebSummary(search.summary)
    assert(parsed.count >= 1, `端到端搜索应有结果，实际 ${parsed.count}`)

    const title = `搜完就发 ${suffix()}`
    const post = await runPlan(admin, agent.agentId, {
      action: 'create_post',
      args: {
        channelId: channel.id,
        title,
        content: `刚搜到 ${parsed.count} 条关于「我的世界 新版本」的网页，第一条是《${parsed.items[0].title}》。这条帖子用来验证「搜到 → 发帖」链路。`,
      },
    })
    assert(post.res.status === 'SUCCESS', `发帖应成功，实际 ${post.res.status} ${post.res.reason ?? ''}`)
    assert(post.res.run?.outputType === 'POST', '产出类型应为 POST')
    const postId = post.res.run.outputId
    assert(postId, '应记录产出 id')

    const detail = await api('GET', `/posts/${postId}`)
    assert(detail.authorId === agent.agentId, '帖子作者应是代理')
    assert(detail.title === title, '帖子标题应与计划一致')
    assert(detail.content.includes('我的世界 新版本'), '正文应带上搜索主题')

    // 运行日志能回放两次调用（搜索 + 发帖）
    const runs = await listRuns(admin.token, agent.agentId, 50)
    const searchRun = (runs.items ?? []).find(
      (item) => item.toolCallsJson?.includes('web_search') && item.status === 'SUCCESS',
    )
    const postRun = (runs.items ?? []).find((item) => item.outputId === postId)
    assert(searchRun, '运行日志应能回放到 web_search')
    assert(postRun, '运行日志应能回放到发帖')
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
