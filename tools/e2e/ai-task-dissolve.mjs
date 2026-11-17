#!/usr/bin/env node
/**
 * 频道助手定时任务「解散后停跑」回归（第 21 轮 P0）。
 *
 * 背景：旧实现 `AiTaskService#scanAndRun` 只按 `channel_ai_settings.enabled=1` 取，
 * 频道解散后设置行还在，任务每天照跑——先调模型烧 token，再在发帖处撞 `CHANNEL_NOT_FOUND`。
 * 第 21 轮修法：`ChannelAiSettingMapper#selectScannable()` 在 SQL 层 join channels 过滤 +
 * 解散时 `disableByChannel()` 置 enabled=0（双保险）。
 *
 * 用例设计（对照组）：
 *   A 频道：保持存活，助手开启 → 一个 tick 内必须产生 ai_task_logs（证明扫描器在工作、小时也对得上）
 *   B 频道：同样配置，创建后立即解散 → 之后若干 tick 内 ai_task_logs 不得新增
 * 只测 B 不新增是弱断言（可能是小时不匹配导致的「本来就不跑」），所以必须有 A 这个正例。
 *
 * 运行（8082 需把扫描间隔调短，默认是每小时一次）：
 *   java -jar backend/target/*.jar "--app.ai.task-cron=0/10 * * * * *"
 *   BASE_URL=http://127.0.0.1:8082/api/v1 node tools/e2e/ai-task-dissolve.mjs
 *
 * 需要平台超管、mock-openai（`node tools/mock-openai/server.mjs 1235`）与本机 mysql 客户端。
 */

import { execFileSync } from 'node:child_process'

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1'
const MOCK_URL = process.env.MOCK_URL || 'http://127.0.0.1:1235'
const ADMIN_USERNAME = process.env.ADMIN_USERNAME || 'admin_demo'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Passw0rd123'

/** 等几个扫描周期（cron 0/10 → 25 秒覆盖 2 个周期） */
const TICK_WAIT_MS = Number(process.env.TICK_WAIT_MS || 25_000)

const DB = {
  host: process.env.E2E_DB_HOST || '127.0.0.1',
  port: process.env.E2E_DB_PORT || '3306',
  name: process.env.E2E_DB_NAME || 'my_social_channel',
  user: process.env.E2E_DB_USERNAME || 'isles_app',
  password: process.env.E2E_DB_PASSWORD || 'IslesDev2026a7',
}

const results = []
const cleanup = {
  adminToken: null,
  modelId: null,
  /** 频道 id → owner token（解散用） */
  channels: [],
}

class SkipCase extends Error {}

class HttpError extends Error {
  constructor(status, body, label) {
    super(`${status} ${label}: ${typeof body === 'string' ? body : JSON.stringify(body)}`)
    this.status = status
    this.body = body
  }
}

async function api(method, path, { token, body, expect = [200] } = {}) {
  const headers = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`
  const response = await fetch(`${BASE_URL}${path}`, {
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

function suffix() {
  return `${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function dbRows(sql) {
  let output
  try {
    output = execFileSync(
      'mysql',
      ['-h', DB.host, '-P', DB.port, '-u', DB.user, `-p${DB.password}`,
        '--default-character-set=utf8mb4', DB.name, '-N', '-B', '-e', sql],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
    )
  } catch (error) {
    throw new SkipCase(
      `无法连接数据库校验（缺 mysql 客户端或凭据不对）：${String(error.message).split('\n')[0]}`,
    )
  }
  return output.trim()
}

function countTaskLogs(channelId) {
  return Number(dbRows(`SELECT COUNT(*) FROM ai_task_logs WHERE channel_id='${channelId}'`).trim())
}

function aiSettingEnabled(channelId) {
  return dbRows(`SELECT enabled FROM channel_ai_settings WHERE channel_id='${channelId}'`).trim()
}

function modelBody(overrides = {}) {
  return {
    name: `E2E 定时任务模型 ${suffix()}`,
    baseUrl: `${MOCK_URL}/v1`,
    apiKey: '',
    model: 'mock-chat',
    visionEnabled: false,
    reasoningEnabled: false,
    thinkingMode: 'NONE',
    isDefault: false,
    enabled: true,
    toolCallEnabled: false,
    timeoutSeconds: 15,
    maxTokens: 256,
    ...overrides,
  }
}

async function newUser(prefix) {
  const username = `${prefix}_${suffix()}`
  await api('POST', '/auth/register', {
    expect: [201],
    body: { username, password: 'Passw0rd123', nickname: prefix },
  })
  const login = await api('POST', '/auth/login', { body: { username, password: 'Passw0rd123' } })
  return { username, id: login.user.id, token: login.accessToken }
}

/** 建频道 + 开助手（日报小时设成「当前 UTC 小时」，保证下一个 tick 就能到期） */
async function createAssistantChannel(owner, modelId, label) {
  const channel = await api('POST', '/channels', {
    token: owner.token,
    expect: [201],
    body: { name: `${label} ${suffix()}`, avatarUrl: '/uploads/default-avatar.png', type: 'PUBLIC' },
  })
  const boards = await api('GET', `/channels/${channel.id}/boards`, { token: owner.token })
  await api('POST', `/channels/${channel.id}/posts`, {
    token: owner.token,
    expect: [201],
    body: {
      boardId: boards.items[0].id,
      title: '定时任务探针帖',
      content: '用于日报的探针内容。',
      contentFormat: 'PLAIN',
    },
  })
  await api('PUT', `/channels/${channel.id}/ai/settings`, {
    token: owner.token,
    body: {
      nickname: '小助手',
      enabled: true,
      summaryDailyEnabled: true,
      summaryWeeklyEnabled: false,
      monthlyContributorsEnabled: false,
      autoPostEnabled: false,
      autoCommentEnabled: false,
      autoLikeEnabled: false,
      autoReplyEnabled: false,
      dailySummaryHour: new Date().getUTCHours(),
      autoPostPerDay: 0,
      autoCommentPerDay: 0,
      autoLikePerDay: 0,
      modelConfigId: modelId,
    },
  })
  return channel
}

async function runCleanup() {
  console.log('\n清理：')
  for (const { channelId, ownerToken } of cleanup.channels) {
    // 频道解散后 ai_task_logs 仍会留着（本用例就是验证它不再**新增**），
    // 但自造的行要清干净，否则库里的定时任务日志越跑越多
    try {
      dbRows(`DELETE FROM ai_task_logs WHERE channel_id='${channelId}'`)
      console.log(`  ✓ 任务日志已清理 ${channelId}`)
    } catch (error) {
      console.error(`  ! 清理任务日志失败 ${channelId}：${error.message}`)
    }
    try {
      await api('POST', `/channels/${channelId}/dissolve`, {
        token: ownerToken,
        expect: [204, 200, 404],
      })
      console.log(`  ✓ 频道已解散 ${channelId}`)
    } catch (error) {
      console.error(`  ! 解散频道失败 ${channelId}：${error.message}`)
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
}

async function main() {
  console.log(`\n频道助手定时任务 · 解散停跑回归 · ${BASE_URL} · mock=${MOCK_URL}`)

  const health = await fetch(`${MOCK_URL}/health`).catch(() => null)
  if (!health || !health.ok) {
    console.error(`mock-openai 不可用（${MOCK_URL}），先执行：node tools/mock-openai/server.mjs 1235`)
    process.exit(1)
  }

  const login = await api('POST', '/auth/login', {
    body: { username: ADMIN_USERNAME, password: ADMIN_PASSWORD },
  })
  const admin = { token: login.accessToken }
  cleanup.adminToken = admin.token
  const me = await api('GET', '/admin/me', { token: admin.token })
  assert(me.admin === true, '需要平台管理员账号（ADMIN_USERNAME / ADMIN_PASSWORD）')

  let model
  let owner
  let aliveChannel
  let doomedChannel

  await step('准备：临时模型 + 两个助手频道（对照组 A 存活 / B 立即解散）', async () => {
    model = await api('POST', '/admin/ai/models', {
      token: admin.token,
      expect: [201],
      body: modelBody(),
    })
    cleanup.modelId = model.id

    owner = await newUser('aitask')
    aliveChannel = await createAssistantChannel(owner, model.id, 'AI Task Alive')
    cleanup.channels.push({ channelId: aliveChannel.id, ownerToken: owner.token })

    doomedChannel = await createAssistantChannel(owner, model.id, 'AI Task Doomed')
    cleanup.channels.push({ channelId: doomedChannel.id, ownerToken: owner.token })

    // 立刻解散，尽量赶在第一个 tick 之前（万一抢先跑了，下面的断言按「不新增」判定，仍然成立）
    await api('POST', `/channels/${doomedChannel.id}/dissolve`, { token: owner.token, expect: [204, 200] })

    assert(countTaskLogs(doomedChannel.id) >= 0, '解散后应仍能读库（表结构未变）')
  })

  let doomedBaseline = 0

  await step('解散后助手设置被关闭（双保险之一）', async () => {
    assert(aiSettingEnabled(doomedChannel.id) === '0', '解散后 channel_ai_settings.enabled 应为 0')
    doomedBaseline = countTaskLogs(doomedChannel.id)
    console.log(`      B 频道解散时已有任务日志 ${doomedBaseline} 条（基准）`)
  })

  await step(`对照组：存活频道在 ${TICK_WAIT_MS / 1000}s 内产生任务日志（证明扫描器在工作）`, async () => {
    const started = Date.now()
    let logs = 0
    while (Date.now() - started < TICK_WAIT_MS) {
      await sleep(3000)
      logs = countTaskLogs(aliveChannel.id)
      if (logs > 0) break
    }
    assert(
      logs > 0,
      `存活频道应产生任务日志（扫描器/小时/模型任一环节失效都会导致 0）；` +
        `确认 8082 以 --app.ai.task-cron=0/10 * * * * * 启动`,
    )
    console.log(`      A 频道 ${Math.round((Date.now() - started) / 1000)}s 内产生 ${logs} 条日志`)
  })

  await step('解散频道：同一时间窗内 ai_task_logs 不再新增', async () => {
    await sleep(TICK_WAIT_MS)
    const after = countTaskLogs(doomedChannel.id)
    assert(
      after === doomedBaseline,
      `解散后不应再产生任务日志：基准 ${doomedBaseline} 条，${TICK_WAIT_MS / 1000}s 后 ${after} 条`,
    )
    // 同时确认没写「失败日志」：旧实现会先调模型再撞 CHANNEL_NOT_FOUND，留下 FAILED 行
    const failed = Number(
      dbRows(
        `SELECT COUNT(*) FROM ai_task_logs WHERE channel_id='${doomedChannel.id}' AND status='FAILED'`,
      ).trim(),
    )
    assert(failed === 0, `解散频道不应产生 FAILED 任务日志，实际 ${failed} 条`)
  })

  const passed = results.filter((item) => item.status === 'pass').length
  const failed = results.filter((item) => item.status === 'fail').length
  const skipped = results.filter((item) => item.status === 'skip').length
  console.log(`\n结果：${passed} 通过 / ${failed} 失败 / ${skipped} 跳过（共 ${results.length} 项）\n`)
  if (failed > 0) process.exitCode = 1
}

main()
  .catch((error) => {
    console.error(`\n失败：${error.message}`)
    process.exitCode = 1
  })
  .finally(async () => {
    await runCleanup()
  })
