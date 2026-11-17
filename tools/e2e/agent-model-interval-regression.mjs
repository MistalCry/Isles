#!/usr/bin/env node
/**
 * AI 代理「发帖间隔配置」与「per-agent 模型」回归（第 19 轮）。
 *
 * 覆盖：
 *   A. agent.minPostIntervalMinutes=0 → 连续两次 run-now 都成功（不再靠回拨数据库）；
 *   B. =60 → 第二次 GUARDED「距上次发帖不足 …」，且改配置后**不重启立即生效**（PUT 配置中心再 run-now）；
 *   C. per-agent 模型：两个代理分别指定不同模型 → 各自 agent_runs.model 是自己的模型；
 *   D. 指定模型被禁用 → 回落平台默认，error 含「已回落默认」；
 *   E. 创建代理时传不存在的 modelConfigId → 422 AGENT_MODEL_UNAVAILABLE。
 *
 * 前置：
 *   - mock-openai：`node tools/mock-openai/server.mjs 1235`（单轮队列 /debug/agent-plan 的 plans）
 *   - 平台超管：ADMIN_USERNAME / ADMIN_PASSWORD
 *   - 本机 mysql 客户端（校验 agent_runs.model_config_id；`E2E_DB_*` 可覆盖）
 *
 * 运行：BASE_URL=http://127.0.0.1:8082/api/v1 MOCK_URL=http://127.0.0.1:1235 \
 *         node tools/e2e/agent-model-interval-regression.mjs
 */

import { execFileSync } from 'node:child_process'

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1'
const MOCK_URL = process.env.MOCK_URL || 'http://127.0.0.1:1235'
const ADMIN_USERNAME = process.env.ADMIN_USERNAME || 'admin_demo'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Passw0rd123'

const CONFIG_KEYS = [
  'agent.enabled',
  'agent.twoPhaseEnabled',
  'agent.minPostIntervalMinutes',
  'agent.minCommentIntervalMinutes',
  'agent.dailyPostLimit',
]

const results = []
const cleanup = {
  agentIds: [],
  channelId: null,
  ownerToken: null,
  adminToken: null,
  modelIds: [],
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

async function apiRaw(method, path, { token, body, expect = [200], base = BASE_URL } = {}) {
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
  return { status: response.status, data }
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

function modelBody(name, model, overrides = {}) {
  return {
    name,
    baseUrl: `${MOCK_URL}/v1`,
    apiKey: '',
    model,
    visionEnabled: false,
    reasoningEnabled: false,
    thinkingMode: 'NONE',
    isDefault: false,
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
    displayName: `模型代理${suffix()}`.slice(0, 20),
    bio: 'E2E 模型/间隔代理',
    interests: ['我的世界'],
    tone: 'casual',
    activeHours: [],
    postsPerDay: 10,
    commentsPerDay: 2,
    channels: [],
    toolsEnabled: [],
    status: 'ACTIVE',
    ...overrides,
  }
}

async function clearPlans() {
  await api('DELETE', '/debug/agent-plan', { base: MOCK_URL }).catch(() => null)
}

async function queuePlan(plan) {
  return api('POST', '/debug/agent-plan', { base: MOCK_URL, body: { plans: [plan] } })
}

async function listRuns(token, agentId, size = 20) {
  return api('GET', `/admin/agents/${agentId}/runs?page=1&size=${size}`, { token })
}

/** 发一条帖（单轮队列），返回 run-now 响应 */
async function postOnce(admin, agentId, channelId, label) {
  await clearPlans()
  await queuePlan({
    action: 'create_post',
    args: {
      channelId,
      title: `${label} ${suffix()}`,
      content: `${label} 的正文，用来验证发帖间隔配置与模型选择。`,
    },
  })
  return api('POST', `/admin/agents/${agentId}/run-now`, { token: admin.token })
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

  for (const modelId of cleanup.modelIds) {
    try {
      await api('DELETE', `/admin/ai/models/${modelId}`, { token: cleanup.adminToken, expect: [204, 404] })
      console.log(`  ✓ 临时模型配置已删除 ${modelId}`)
    } catch (error) {
      console.error(`  ! 删除临时模型配置失败 ${modelId}：${error.message}`)
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
  console.log(`\nAI 代理模型/间隔回归 · ${BASE_URL} · mock=${MOCK_URL}`)

  const login = await api('POST', '/auth/login', {
    body: { username: ADMIN_USERNAME, password: ADMIN_PASSWORD },
  })
  const admin = { token: login.accessToken, id: login.user.id }
  cleanup.adminToken = admin.token

  const me = await api('GET', '/admin/me', { token: admin.token })
  assert(me.admin === true, '需要平台管理员账号（ADMIN_USERNAME / ADMIN_PASSWORD）')

  let owner
  let channel
  let agentA
  let modelA
  let modelB
  let modelC

  await step('准备：三个临时模型（A=默认 / B / C）+ 频道 + 代理 + 配置快照', async () => {
    const models = await api('GET', '/admin/ai/models', { token: admin.token })
    cleanup.restoreDefaultModels = (models.items ?? [])
      .filter((item) => item.isDefault)
      .map((item) => ({ id: item.id, request: toModelRequest(item) }))

    modelA = await api('POST', '/admin/ai/models', {
      token: admin.token,
      expect: [201],
      body: modelBody(`E2E Model A ${suffix()}`, 'mock-chat', { isDefault: true }),
    })
    cleanup.modelIds.push(modelA.id)
    modelB = await api('POST', '/admin/ai/models', {
      token: admin.token,
      expect: [201],
      body: modelBody(`E2E Model B ${suffix()}`, 'mock-vision'),
    })
    cleanup.modelIds.push(modelB.id)
    modelC = await api('POST', '/admin/ai/models', {
      token: admin.token,
      expect: [201],
      body: modelBody(`E2E Model C ${suffix()}`, 'mock-reasoner'),
    })
    cleanup.modelIds.push(modelC.id)
    assert(modelA.isDefault === true, '模型 A 应是默认模型')

    await snapshotConfigs(admin.token)
    await setConfig(admin.token, 'agent.enabled', 'true')
    // 单轮模式：本脚本验的是间隔与模型选择，不需要研究阶段
    await setConfig(admin.token, 'agent.twoPhaseEnabled', 'false')
    await setConfig(admin.token, 'agent.minPostIntervalMinutes', '0')

    owner = await newUser('mdown')
    cleanup.ownerToken = owner.token
    channel = await api('POST', '/channels', {
      token: owner.token,
      expect: [201],
      body: { name: `Model Audit ${suffix()}`, avatarUrl: '/uploads/default-avatar.png', type: 'PUBLIC' },
    })
    cleanup.channelId = channel.id

    const created = await api('POST', '/admin/agents', {
      token: admin.token,
      expect: [201],
      body: agentBody({ channels: [channel.id] }),
    })
    agentA = { ...created, agentId: created.userId }
    cleanup.agentIds.push(agentA.agentId)
  })

  // ------------------------------------------------------------------
  // A. 间隔 0：连续两次都成功
  // ------------------------------------------------------------------
  await step('minPostIntervalMinutes=0：连续两次 run-now 都成功', async () => {
    const first = await postOnce(admin, agentA.agentId, channel.id, '间隔0-第一帖')
    assert(first.status === 'SUCCESS', `第一次应成功，实际 ${first.status} ${first.reason ?? ''}`)
    assert(first.run?.outputType === 'POST', `第一次产出应为 POST，实际 ${first.run?.outputType}`)

    const second = await postOnce(admin, agentA.agentId, channel.id, '间隔0-第二帖')
    assert(
      second.status === 'SUCCESS',
      `间隔 0 时第二次也应成功（无需等 2 小时），实际 ${second.status} ${second.reason ?? ''}`,
    )
    assert(second.run?.outputType === 'POST', `第二次产出应为 POST，实际 ${second.run?.outputType}`)
  })

  // ------------------------------------------------------------------
  // B. 间隔 60：第二次被拦；改配置立即生效
  // ------------------------------------------------------------------
  await step('minPostIntervalMinutes=60：下一次发帖 GUARDED「距上次发帖不足 …」', async () => {
    // 走配置中心 PUT，不重启
    await setConfig(admin.token, 'agent.minPostIntervalMinutes', '60')
    const res = await postOnce(admin, agentA.agentId, channel.id, '间隔60-应被拦')
    assert(res.status === 'GUARDED', `应被间隔闸门拦下，实际 ${res.status} ${res.reason ?? ''}`)
    assert(
      /距上次发帖不足\s*(60\s*分钟|1\s*小时)/.test(res.reason ?? ''),
      `理由应说明间隔不足（60 分钟 / 1 小时），实际「${res.reason}」`,
    )
  })

  await step('改回 0 后立即生效：同一进程内下一次发帖又成功（不重启）', async () => {
    await setConfig(admin.token, 'agent.minPostIntervalMinutes', '0')
    const res = await postOnce(admin, agentA.agentId, channel.id, '改回0-立即生效')
    assert(
      res.status === 'SUCCESS',
      `配置改回 0 后应立即放行，实际 ${res.status} ${res.reason ?? ''}`,
    )
  })

  // ------------------------------------------------------------------
  // C. per-agent 模型
  // ------------------------------------------------------------------
  let agentB

  await step('per-agent 模型：两个代理各用各的模型（agent_runs.model 对得上）', async () => {
    // A 指定模型 B（mock-vision）
    const updated = await api('PUT', `/admin/agents/${agentA.agentId}`, {
      token: admin.token,
      body: agentBody({
        channels: [channel.id],
        displayName: agentA.displayName,
        modelConfigId: modelB.id,
      }),
    })
    assert(updated.modelConfigId === modelB.id, `A 应记录指定模型，实际 ${updated.modelConfigId}`)
    assert(updated.modelName === modelB.name, `A 的生效模型名应是 ${modelB.name}，实际 ${updated.modelName}`)

    // 新代理 B 指定模型 A（mock-chat，同时是平台默认）
    const created = await api('POST', '/admin/agents', {
      token: admin.token,
      expect: [201],
      body: agentBody({ channels: [channel.id], modelConfigId: modelA.id }),
    })
    agentB = { ...created, agentId: created.userId }
    cleanup.agentIds.push(agentB.agentId)
    assert(agentB.modelName === modelA.name, `B 的生效模型名应是 ${modelA.name}，实际 ${agentB.modelName}`)

    const runA = await postOnce(admin, agentA.agentId, channel.id, '模型B-发帖')
    assert(runA.status === 'SUCCESS', `A 发帖应成功，实际 ${runA.status} ${runA.reason ?? ''}`)
    assert(runA.run?.model === 'mock-vision', `A 的运行应记录 mock-vision，实际 ${runA.run?.model}`)

    const runB = await postOnce(admin, agentB.agentId, channel.id, '模型A-发帖')
    assert(runB.status === 'SUCCESS', `B 发帖应成功，实际 ${runB.status} ${runB.reason ?? ''}`)
    assert(runB.run?.model === 'mock-chat', `B 的运行应记录 mock-chat，实际 ${runB.run?.model}`)

    // 落库对账：agent_runs.model_config_id 也要对上
    const dbModelA = dbValue(`SELECT model_config_id FROM agent_runs WHERE id='${runA.run.id}'`)
    const dbModelB = dbValue(`SELECT model_config_id FROM agent_runs WHERE id='${runB.run.id}'`)
    assert(dbModelA === modelB.id, `A 的运行应记录 model_config_id=${modelB.id}，实际 ${dbModelA}`)
    assert(dbModelB === modelA.id, `B 的运行应记录 model_config_id=${modelA.id}，实际 ${dbModelB}`)
  })

  // ------------------------------------------------------------------
  // D. 指定模型被禁用 → 回落默认
  // ------------------------------------------------------------------
  await step('指定模型被禁用：回落平台默认，error 含「已回落默认」', async () => {
    const created = await api('POST', '/admin/agents', {
      token: admin.token,
      expect: [201],
      body: agentBody({ channels: [channel.id], modelConfigId: modelC.id }),
    })
    const agentC = { ...created, agentId: created.userId }
    cleanup.agentIds.push(agentC.agentId)
    assert(agentC.modelConfigId === modelC.id, 'C 创建时应接受启用中的模型 C')

    // 禁用模型 C（走模型管理接口）
    const models = await api('GET', '/admin/ai/models', { token: admin.token })
    const target = (models.items ?? []).find((item) => item.id === modelC.id)
    assert(target, '模型列表应包含 C')
    await api('PUT', `/admin/ai/models/${modelC.id}`, {
      token: admin.token,
      body: toModelRequest(target, { enabled: false }),
    })

    const res = await postOnce(admin, agentC.agentId, channel.id, '回落默认-发帖')
    assert(res.status === 'SUCCESS', `回落默认后应能正常发帖，实际 ${res.status} ${res.reason ?? ''}`)
    assert(res.run?.model === 'mock-chat', `应回落到默认模型 mock-chat，实际 ${res.run?.model}`)
    assert(
      (res.run?.error ?? '').includes('已回落默认'),
      `运行日志的 error 应说明已回落默认，实际「${res.run?.error}」`,
    )
  })

  // ------------------------------------------------------------------
  // E. 不存在的 modelConfigId → 422
  // ------------------------------------------------------------------
  await step('创建代理传不存在的 modelConfigId → 422 AGENT_MODEL_UNAVAILABLE', async () => {
    // 26 位（与 ULID 同长）：否则会先被 @Size(max=26) 拦成 400，测不到「不存在」这条
    const fakeId = '01' + 'NOTEXIST'.padEnd(24, '0')
    assert(fakeId.length === 26, '测试用 id 长度应为 26（与 ULID 一致，避免触发 @Size 400）')
    const created = await apiRaw('POST', '/admin/agents', {
      token: admin.token,
      expect: [201, 422],
      body: agentBody({ channels: [channel.id], modelConfigId: fakeId }),
    })
    assert(created.status === 422, `应返回 422，实际 ${created.status}（成功创建了代理）`)
    assert(
      created.data?.error?.code === 'AGENT_MODEL_UNAVAILABLE',
      `错误码应为 AGENT_MODEL_UNAVAILABLE，实际 ${created.data?.error?.code}`,
    )

    // 更新同样要拦（避免「创建拦、更新放」的漏洞）
    const updated = await apiRaw('PUT', `/admin/agents/${agentA.agentId}`, {
      token: admin.token,
      expect: [200, 422],
      body: agentBody({
        channels: [channel.id],
        displayName: agentA.displayName,
        modelConfigId: fakeId,
      }),
    })
    assert(updated.status === 422, `更新时也应返回 422，实际 ${updated.status}`)
    assert(
      updated.data?.error?.code === 'AGENT_MODEL_UNAVAILABLE',
      `更新错误码应为 AGENT_MODEL_UNAVAILABLE，实际 ${updated.data?.error?.code}`,
    )

    // 原配置不应被改坏
    const detail = await api('GET', `/admin/agents/${agentA.agentId}`, { token: admin.token })
    assert(detail.modelConfigId === modelB.id, `失败更新不应改动人设，实际 ${detail.modelConfigId}`)
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
