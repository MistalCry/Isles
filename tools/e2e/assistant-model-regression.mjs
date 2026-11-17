#!/usr/bin/env node
/**
 * 频道助手模型回归（P1-5）：
 *   1. 频道设置里选非默认模型 → 任务确实用该模型执行（用 mock-openai 的请求日志证明）；
 *   2. 该模型被禁用 → 任务回落平台默认模型，任务不失败。
 *
 * 关于「任务日志记录模型 id」：当前 `ai_task_logs` 表与 `TaskLogResponse` 都没有 model 字段，
 * 脚本会把这一条标为 SKIP 并在结尾提示，等后端补字段后取消跳过即可（不要为此改业务代码）。
 *
 * 依赖：mock-openai 在 1235（`node tools/mock-openai/server.mjs 1235`）、平台超管账号。
 * 自造数据：临时模型配置（跑完删除）、测试用户与频道（跑完解散）。
 * 注意：第 2 步会真实调用平台默认模型（本地 LM Studio），耗时约 1 分钟；LM Studio 未启动时会失败，
 * 这属于环境问题而不是用例问题。
 */

import { execFileSync } from 'node:child_process'

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1'
const MOCK_URL = (process.env.MOCK_URL || 'http://127.0.0.1:1235').replace(/\/$/, '')
const ADMIN_USERNAME = process.env.ADMIN_USERNAME || 'admin_demo'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Passw0rd123'

const DB = {
  host: process.env.E2E_DB_HOST || '127.0.0.1',
  port: process.env.E2E_DB_PORT || '3306',
  name: process.env.E2E_DB_NAME || 'my_social_channel',
  user: process.env.E2E_DB_USERNAME || 'isles_app',
  password: process.env.E2E_DB_PASSWORD || 'IslesDev2026a7',
}

/**
 * 清理本脚本造的任务日志。频道解散后接口读不到这些行，只能走库；
 * 没有 mysql 客户端时只警告，不判失败（本脚本的核心断言不依赖数据库）。
 */
function deleteTaskLogs(channelId) {
  try {
    execFileSync(
      'mysql',
      ['-h', DB.host, '-P', DB.port, '-u', DB.user, `-p${DB.password}`, DB.name, '-N', '-B',
        '-e', `DELETE FROM ai_task_logs WHERE channel_id='${channelId}'`],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
    )
    console.log(`  ✓ 任务日志已清理 ${channelId}`)
  } catch (error) {
    console.error(`  ! 清理任务日志失败（缺 mysql 客户端？）：${String(error.message).split('\n')[0]}`)
  }
}

const results = []
const cleanup = { modelId: null, channelId: null, ownerToken: null }

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

function skip(name, reason) {
  results.push({ name, status: 'skip', note: reason })
  console.log(`  ⊘ ${name}\n    跳过：${reason}`)
}

/** 用例内部判定「环境不具备/已知缺陷」时抛出，交给 step 记为跳过 */
class SkipCase extends Error {}

let blockedReason = null

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
    if (error instanceof SkipCase) {
      skip(name, error.message)
      return
    }
    results.push({ name, status: 'fail', error: error.message })
    console.error(`  ✗ ${name}\n    ${error.message}`)
  }
}

function suffix() {
  return `${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`
}

/**
 * 解析「平台默认模型」——镜像 `AiModelConfigService#resolveDefault()` 的取法：
 * 先取 enabled && isDefault 的第一条，否则取 enabled 里 createdAt/id 最小的那条。
 *
 * <p>用于把「回落默认」从 `!== 被禁用模型` 收紧成 `=== 具体默认模型`（第 21 轮）：
 * 原来的写法在 modelConfigId 为 null/空时也能过。
 */
async function resolvePlatformDefaultModel(token) {
  const list = await api('GET', '/admin/ai/models', { token })
  const enabled = (list.items ?? []).filter((item) => item.enabled === true)
  if (enabled.length === 0) return null
  const explicit = enabled.find((item) => item.isDefault === true)
  if (explicit) return explicit
  return enabled.slice().sort((a, b) => {
    const ta = a.createdAt ?? ''
    const tb = b.createdAt ?? ''
    if (ta !== tb) return ta < tb ? -1 : 1
    return String(a.id) < String(b.id) ? -1 : 1
  })[0]
}

function modelPayload(overrides = {}) {
  return {
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
    ...overrides,
  }
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
  if (cleanup.channelId) {
    deleteTaskLogs(cleanup.channelId)
  }

  if (cleanup.channelId && cleanup.ownerToken) {
    try {
      await api('POST', `/channels/${cleanup.channelId}/dissolve`, {
        token: cleanup.ownerToken,
        expect: [204, 404],
      })
      console.log('  ✓ 测试频道已解散')
    } catch (error) {
      console.error(`  ! 解散频道失败：${error.message}`)
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
      console.error(`  ! 删除模型配置失败：${error.message}`)
    }
  }
}

async function main() {
  console.log(`\n群岛频道助手模型回归 · ${BASE_URL} · mock=${MOCK_URL}\n`)

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
  let model

  await step('准备：超管登录 + 临时模型配置（enabled，非默认）', async () => {
    const login = await api('POST', '/auth/login', { body: { username: ADMIN_USERNAME, password: ADMIN_PASSWORD } })
    admin = { token: login.accessToken }
    cleanup.adminToken = admin.token
    model = await api('POST', '/admin/ai/models', {
      token: admin.token,
      expect: [201],
      body: { name: `E2E 助手模型 ${suffix()}`, ...modelPayload() },
    })
    cleanup.modelId = model.id
    assert(model.enabled === true, '临时模型应处于启用状态')
    assert(model.isDefault === false, '临时模型不应抢平台默认（避免影响其它人）')
  })

  await step('准备：频道 + 助手开启并指定该模型', async () => {
    const username = `aimodel_${suffix()}`
    await api('POST', '/auth/register', { expect: [201], body: { username, password: 'Passw0rd123', nickname: 'aimodel' } })
    const login = await api('POST', '/auth/login', { body: { username, password: 'Passw0rd123' } })
    user = { token: login.accessToken }
    channel = await api('POST', '/channels', {
      token: user.token,
      expect: [201],
      body: { name: `Assistant Model ${suffix()}`, avatarUrl: '/uploads/default-avatar.png', type: 'PUBLIC' },
    })
    cleanup.channelId = channel.id
    cleanup.ownerToken = user.token
    const boards = await api('GET', `/channels/${channel.id}/boards`)
    await api('POST', `/channels/${channel.id}/posts`, {
      token: user.token,
      expect: [201],
      body: {
        boardId: boards.items[0].id,
        title: '助手模型回归探针帖',
        content: '用于生成日报的探针内容，包含一些讨论。',
        contentFormat: 'PLAIN',
      },
    })
    const settings = await api('PUT', `/channels/${channel.id}/ai/settings`, {
      token: user.token,
      body: {
        nickname: '小助手',
        enabled: true,
        summaryDailyEnabled: true,
        summaryWeeklyEnabled: true,
        monthlyContributorsEnabled: false,
        autoPostEnabled: false,
        autoCommentEnabled: false,
        autoLikeEnabled: false,
        autoReplyEnabled: false,
        dailySummaryHour: 9,
        autoPostPerDay: 0,
        autoCommentPerDay: 0,
        autoLikePerDay: 0,
        modelConfigId: model.id,
      },
    })
    assert(settings.modelConfigId === model.id, `频道设置应保存指定模型，实际 ${settings.modelConfigId}`)
  })

  if (!channel || !model) {
    blockedReason = '前置准备失败（模型配置/频道未创建），常见原因：注册限流 429 或 AI 配置接口不可用'
  }

  await step('指定模型生效：任务实际用频道所选模型执行', async () => {
    await resetMockRequests()
    const taskLog = await api('POST', `/channels/${channel.id}/ai/tasks/run`, {
      token: user.token,
      body: { taskType: 'DAILY_SUMMARY' },
    })
    assert(taskLog.status === 'SUCCESS', `任务应成功，实际 ${taskLog.status} ${taskLog.errorMessage ?? ''}`)

    const requests = await mockRequests()
    assert(requests.length > 0, 'mock 应收到模型请求（说明确实用了频道指定模型）')
    assert(
      requests.every((item) => item.model === 'mock-chat'),
      `请求模型应为 mock-chat，实际 ${requests.map((item) => item.model).join(',')}`,
    )

    // 任务日志必须记录本次使用的模型（V9 新增 model_config_id / model）
    assert(
      taskLog.modelConfigId === model.id,
      `任务日志应记录频道指定模型 id ${model.id}，实际 ${taskLog.modelConfigId}`,
    )
    assert(taskLog.model === 'mock-chat', `任务日志应记录模型名 mock-chat，实际 ${taskLog.model}`)

    // 任务日志列表接口同样要带出模型，后台页面才能核对
    const logs = await api('GET', `/channels/${channel.id}/ai/task-logs?size=10`, { token: user.token })
    const listed = (logs.items ?? []).find((item) => item.id === taskLog.id)
    assert(listed, '任务日志列表应包含刚执行的任务')
    assert(
      listed.modelConfigId === model.id && listed.model === 'mock-chat',
      `日志列表应带模型信息，实际 ${listed.modelConfigId} / ${listed.model}`,
    )
  })

  await step('模型被禁用后回落平台默认，任务不失败', async () => {
    const defaultModel = await resolvePlatformDefaultModel(admin.token)
    if (!defaultModel) {
      throw new SkipCase('平台没有任何启用的模型，无法验证「回落默认」')
    }
    assert(defaultModel.id !== model.id, '平台默认模型不应是即将被禁用的临时模型')
    await api('PUT', `/admin/ai/models/${model.id}`, {
      token: admin.token,
      body: { name: model.name, ...modelPayload({ enabled: false }) },
    })
    await resetMockRequests()
    const taskLog = await api('POST', `/channels/${channel.id}/ai/tasks/run`, {
      token: user.token,
      body: { taskType: 'WEEKLY_SUMMARY' },
    })
    if (taskLog.status === 'FAILED' && (taskLog.errorMessage ?? '').includes('无法连接模型服务')) {
      // 回落目标是「平台默认模型」（本地 LM Studio）。它没启动时任务必然失败，
      // 这不是本用例要验的缺陷，按环境不具备跳过并说明，避免误判为回归失败。
      throw new SkipCase(
        '平台默认模型不可用（LM Studio 未启动），无法验证「禁用后回落默认」；' +
          '启动 LM Studio（默认 1234）后重跑即可，或把默认模型换成可达的服务',
      )
    }
    assert(taskLog.status === 'SUCCESS', `回落默认后任务应成功，实际 ${taskLog.status} ${taskLog.errorMessage ?? ''}`)
    const requests = await mockRequests()
    assert(
      requests.length === 0,
      `已禁用的模型不应再被调用，实际仍收到 ${requests.length} 次请求`,
    )
    // 日志要如实记录「实际用的是默认模型」，且必须是那个具体模型——
    // 原来只写 `!== 被禁用 id`，字段为 null 也能过（第 21 轮收紧）
    assert(
      taskLog.modelConfigId === defaultModel.id,
      `回落默认后日志应记录平台默认模型 ${defaultModel.id}（${defaultModel.name}），实际 ${taskLog.modelConfigId}`,
    )
    assert(
      taskLog.model === defaultModel.model,
      `回落默认后日志的模型名应为 ${defaultModel.model}，实际 ${taskLog.model}`,
    )
    assert(
      taskLog.model !== 'mock-chat',
      `回落默认后日志的模型名不应是 mock-chat，实际 ${taskLog.model}`,
    )
    // 恢复正常状态，便于收尾删除
    await api('PUT', `/admin/ai/models/${model.id}`, {
      token: admin.token,
      body: { name: model.name, ...modelPayload({ enabled: true }) },
    })
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
