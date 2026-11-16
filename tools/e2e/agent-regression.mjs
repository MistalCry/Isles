#!/usr/bin/env node
/**
 * AI 用户代理（V13 / docs/12）P0 回归。
 *
 * A. 主链路（默认模式）：创建代理 → run-now 产出帖子/评论（is_ai_generated=1、作者 is_agent=1）
 *    → 日发帖上限 → 同话题 7 天去重 → 暂停后 run-now → 总开关关闭 → 无可用模型 → 运行日志可回放。
 * B. 调度模式（AGENT_TICK_MODE=1）：验证 tick 会挑选活跃代理、暂停后不再挑选、总开关关闭后全站停止。
 *    需要 8082 以 `--app.agent.tick-cron=0/10 * * * * *` 启动（默认 5 分钟一次，等不起）。
 *
 * 前置：
 *   - mock-openai：`node tools/mock-openai/server.mjs 1235`（脚本用 /debug/agent-plan 注入计划）
 *   - 平台超管：ADMIN_USERNAME / ADMIN_PASSWORD（默认 admin_demo / Passw0rd123）
 *   - 本机 mysql 客户端（校验 is_agent / is_ai_generated / 级联清理；缺失时相关用例跳过）
 *
 * 说明：代理固定用平台**默认模型**（AgentRuntime#resolveDefault），所以脚本会临时建一个指向 mock 的
 * 默认模型配置，跑完删除；原有的默认标记（is_default）本来全是 0，删除后状态与运行前一致。
 *
 * 运行：BASE_URL=http://127.0.0.1:8082/api/v1 MOCK_URL=http://127.0.0.1:1235 node tools/e2e/agent-regression.mjs
 */

import { execFileSync } from 'node:child_process'

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1'
const MOCK_URL = process.env.MOCK_URL || 'http://127.0.0.1:1235'
const ADMIN_USERNAME = process.env.ADMIN_USERNAME || 'admin_demo'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Passw0rd123'
/** 1 = 调度模式（验证 tick），默认主链路模式 */
const TICK_MODE = process.env.AGENT_TICK_MODE === '1'
/** 等 tick 挑中活跃代理的上限（概率 0.3/tick，10 秒一 tick） */
const TICK_WAIT_MS = Number(process.env.AGENT_TICK_WAIT_MS || 90_000)
/** 暂停/关闭后观察「不再挑选」的窗口（≥2 个 tick 周期） */
const TICK_QUIET_MS = Number(process.env.AGENT_TICK_QUIET_MS || 25_000)

const results = []
const cleanup = {
  agentIds: [],
  channelId: null,
  ownerToken: null,
  adminToken: null,
  modelId: null,
  /** key → { value, overridden }：收尾按原状还原 */
  configs: new Map(),
  /** 运行前已有的默认模型：临时配置会清掉它们的 is_default，收尾要还回去 */
  restoreDefaultModels: [],
}

class HttpError extends Error {
  constructor(status, body, label) {
    super(`${status} ${label}: ${typeof body === 'string' ? body : JSON.stringify(body)}`)
    this.status = status
    this.body = body
  }
}

/** 用例内部判定「环境不具备/已知缺口」时抛出，交给 step 记为跳过 */
class SkipCase extends Error {}

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
// mock-openai 控制
// ---------------------------------------------------------------------------

/** 注入代理计划（先进先出，每次计划请求消费一条） */
async function queuePlans(plans) {
  return api('POST', '/debug/agent-plan', { base: MOCK_URL, body: { plans } })
}

async function clearPlans() {
  return api('DELETE', '/debug/agent-plan', { base: MOCK_URL }).catch(() => null)
}

async function mockRequests() {
  const data = await api('GET', '/debug/requests', { base: MOCK_URL })
  return data.items ?? []
}

async function clearMockRequests() {
  await api('DELETE', '/debug/requests', { base: MOCK_URL }).catch(() => null)
}

// ---------------------------------------------------------------------------
// 数据库校验（is_agent / is_ai_generated / 运行日志清理只能从表里看）
// ---------------------------------------------------------------------------

// 用 E2E_ 前缀：外层 shell 里常有项目无关的 DB_* 变量，直接读会被带偏
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

function dbValue(sql) {
  const rows = dbExec(sql)
    .trim()
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
  return rows[0] ?? ''
}

// ---------------------------------------------------------------------------
// 业务辅助
// ---------------------------------------------------------------------------

function modelBody(overrides = {}) {
  return {
    name: `E2E Mock Agent ${suffix()}`,
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

/** 把 GET 到的模型配置转成 PUT 需要的请求体（apiKey 传空 = 保持原值） */
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

/** 代理人设请求体（PUT 时改某个字段直接覆盖） */
function agentBody(overrides = {}) {
  return {
    displayName: `代理${suffix()}`.slice(0, 20),
    bio: 'E2E 自动化代理',
    interests: ['海岛', '潮汐'],
    tone: 'casual',
    activeHours: [],
    postsPerDay: 2,
    commentsPerDay: 2,
    channels: [],
    toolsEnabled: [],
    status: 'ACTIVE',
    ...overrides,
  }
}

/**
 * 路径参数用 `userId`（后端 requireProfile → selectByUserId）。
 * 注意 AgentView 里的 `id` 是人设行 id，不能当路径参数用——前端也是拿 userId 调的。
 */
async function runNow(token, agentId) {
  return api('POST', `/admin/agents/${agentId}/run-now`, { token })
}

async function listRuns(token, agentId, size = 20) {
  return api('GET', `/admin/agents/${agentId}/runs?page=1&size=${size}`, { token })
}

const CONFIG_KEYS = ['agent.enabled', 'agent.twoPhaseEnabled']

/** 记录配置原状（有覆盖值记值，没有则收尾 reset） */
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

async function setAgentEnabled(token, enabled) {
  return setConfig(token, 'agent.enabled', enabled ? 'true' : 'false')
}

// ---------------------------------------------------------------------------
// 清理
// ---------------------------------------------------------------------------

async function runCleanup() {
  console.log('\n清理：')
  await clearPlans()

  if (cleanup.agentIds.length > 0) {
    let adminToken = null
    try {
      const login = await api('POST', '/auth/login', {
        body: { username: ADMIN_USERNAME, password: ADMIN_PASSWORD },
      })
      adminToken = login.accessToken
    } catch {
      adminToken = null
    }
    for (const agentId of cleanup.agentIds) {
      if (adminToken) {
        try {
          await api('DELETE', `/admin/agents/${agentId}`, { token: adminToken, expect: [204, 404] })
          console.log(`  ✓ 代理已删除 ${agentId}`)
        } catch (error) {
          console.error(`  ! 删除代理失败 ${agentId}：${error.message}`)
        }
      }
      // 删除代理不会清运行日志/记忆（内容保留策略）：测试造的行自己收干净。
      // 账号行保留（已是 DISABLED）：posts/comments.author_id 是 FK RESTRICT，硬删账号会失败，
      // 而且「删除代理保留内容」本身就是产品约定。
      try {
        dbExec(`DELETE FROM agent_runs WHERE agent_id='${agentId}'`)
        dbExec(`DELETE FROM agent_memories WHERE agent_id='${agentId}'`)
        console.log(`  ✓ 代理运行日志/记忆已清理 ${agentId}`)
      } catch (error) {
        console.error(`  ! 清理代理数据失败 ${agentId}：${error.message}`)
      }
    }
  }

  if (cleanup.configs.size > 0 && cleanup.adminToken) {
    await restoreConfigs()
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

  // 还原本来的默认模型（临时默认配置会把它们的 is_default 清成 0）
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
// 准备（两种模式共用）
// ---------------------------------------------------------------------------

async function prepare(admin) {
  let owner
  let channel
  let board
  await step('准备：超管登录 + 临时默认模型（指向 mock）+ 频道与板块', async () => {
    const models = await api('GET', '/admin/ai/models', { token: admin.token })
    const existingDefaults = (models.items ?? []).filter((item) => item.isDefault)
    // 别人可能已经设了默认模型（团队联调常用 LM Studio）：先记下来，收尾还原，
    // 不能因为「创建默认模型会清掉别人的 is_default」就把平台配置改坏。
    cleanup.restoreDefaultModels = existingDefaults.map((item) => ({
      id: item.id,
      request: toModelRequest(item),
    }))
    if (existingDefaults.length > 0) {
      console.log(`      运行前已有默认模型 ${existingDefaults.map((m) => m.name).join(',')}，收尾会还原`)
    }

    const model = await api('POST', '/admin/ai/models', {
      token: admin.token,
      expect: [201],
      body: modelBody(),
    })
    cleanup.modelId = model.id
    assert(model.isDefault === true, '临时模型应成为默认模型（代理固定用默认模型）')

    await snapshotConfigs(admin.token)
    // 默认两阶段（研究→产出）：本脚本验的是单轮闸门，显式关掉，收尾还原
    await setConfig(admin.token, 'agent.twoPhaseEnabled', 'false')

    owner = await newUser('agown')
    cleanup.ownerToken = owner.token
    channel = await api('POST', '/channels', {
      token: owner.token,
      expect: [201],
      body: { name: `Agent Audit ${suffix()}`, avatarUrl: '/uploads/default-avatar.png', type: 'PUBLIC' },
    })
    cleanup.channelId = channel.id
    board = (await api('GET', `/channels/${channel.id}/boards`)).items[0]
    assert(board, '频道应自带默认板块')
  })
  return { owner, channel, board }
}

async function createAgent(admin, channelId, overrides = {}) {
  const created = await api('POST', '/admin/agents', {
    token: admin.token,
    expect: [201],
    body: agentBody({ channels: [channelId], ...overrides }),
  })
  // 统一把「账号 id」暴露成 agentId，路径参数一律用它
  const agent = { ...created, agentId: created.userId }
  cleanup.agentIds.push(agent.agentId)
  return agent
}

// ---------------------------------------------------------------------------
// A. 主链路
// ---------------------------------------------------------------------------

async function mainFlow(admin) {
  const { owner, channel, board } = await prepare(admin)
  if (!channel) return

  let agent
  let postId
  let commentId
  const postTitle = `E2E 代理话题 ${suffix()}`

  await step('创建代理：账号 is_agent=1，人设 ACTIVE', async () => {
    agent = await createAgent(admin, channel.id)
    assert(agent.status === 'ACTIVE', `新代理应为 ACTIVE，实际 ${agent.status}`)
    assert(agent.username.startsWith('agent_'), `用户名应自动生成 agent_ 前缀，实际 ${agent.username}`)
    assert(agent.agentId, '创建响应应带账号 id（userId）')

    const isAgent = dbValue(`SELECT is_agent FROM users WHERE id='${agent.agentId}'`)
    assert(isAgent === '1', `账号 is_agent 应为 1，实际 ${isAgent}`)

    const list = await api('GET', '/admin/agents', { token: admin.token })
    assert(
      (list.items ?? []).some((item) => item.userId === agent.agentId),
      '代理列表应包含刚创建的代理',
    )
  })

  await step('总开关：agent.enabled=true 后 run-now 能产出帖子（is_ai_generated=1）', async () => {
    await setAgentEnabled(admin.token, true)
    await clearMockRequests()
    await queuePlans([
      {
        action: 'create_post',
        args: { channelId: channel.id, title: postTitle, content: `${postTitle} 的正文，聊聊潮汐与灯塔的日常。` },
      },
    ])

    const res = await runNow(admin.token, agent.agentId)
    assert(res.status === 'SUCCESS', `run-now 应成功，实际 ${res.status} ${res.reason ?? ''}`)
    assert(res.run?.outputType === 'POST', `产出类型应为 POST，实际 ${res.run?.outputType}`)
    postId = res.run.outputId
    assert(postId, '运行日志应记录产出 id')

    const post = await api('GET', `/posts/${postId}`)
    assert(post.authorId === agent.agentId, `帖子作者应为代理 ${agent.agentId}，实际 ${post.authorId}`)
    assert(post.title === postTitle, `帖子标题应与计划一致，实际 ${post.title}`)

    const flag = dbValue(`SELECT is_ai_generated FROM posts WHERE id='${postId}'`)
    assert(flag === '1', `帖子 is_ai_generated 应为 1，实际 ${flag}`)

    const requests = await mockRequests()
    assert(requests.length >= 1, '应至少有一次模型请求')
    assert(
      requests.some((item) => item.agentPlan?.action === 'create_post'),
      '模型请求应携带代理计划（mock 侧应记录 agentPlan）',
    )
  })

  await step('run-now 产出评论（is_ai_generated=1，前台可见）', async () => {
    await queuePlans([
      { action: 'comment', args: { postId, content: `这条评论来自代理，针对《${postTitle}》。` } },
    ])
    const res = await runNow(admin.token, agent.agentId)
    assert(res.status === 'SUCCESS', `run-now 应成功，实际 ${res.status} ${res.reason ?? ''}`)
    assert(res.run?.outputType === 'COMMENT', `产出类型应为 COMMENT，实际 ${res.run?.outputType}`)
    commentId = res.run.outputId

    const flag = dbValue(`SELECT is_ai_generated FROM comments WHERE id='${commentId}'`)
    assert(flag === '1', `评论 is_ai_generated 应为 1，实际 ${flag}`)

    const comments = await api('GET', `/posts/${postId}/comments?limit=50`)
    const mine = (comments.items ?? []).find((item) => item.id === commentId)
    assert(mine, '评论应出现在前台评论列表')
    assert(mine.authorId === agent.agentId, '评论作者应为代理账号')
  })

  await step('AI 标识字段：代理内容 true、真人内容 false（帖子/评论/作者/个人资料）', async () => {
    // 真人对照：同频道一帖、同帖子一评。只测 true 挡不住回归，false 才是防线。
    const humanPost = await api('POST', `/channels/${channel.id}/posts`, {
      token: owner.token,
      expect: [201],
      body: {
        boardId: board.id,
        title: `真人帖 ${suffix()}`,
        content: '真人发的内容，AI 标识应为 false。',
        contentFormat: 'PLAIN',
      },
    })
    const humanComment = await api('POST', `/posts/${postId}/comments`, {
      token: owner.token,
      expect: [201],
      body: { content: '真人评论，不是 AI 生成。' },
    })

    // 1) 帖子详情
    const agentPost = await api('GET', `/posts/${postId}`)
    assert(agentPost.isAiGenerated === true, `代理帖 isAiGenerated 应为 true，实际 ${agentPost.isAiGenerated}`)
    assert(agentPost.authorIsAgent === true, `代理帖 authorIsAgent 应为 true，实际 ${agentPost.authorIsAgent}`)
    const humanDetail = await api('GET', `/posts/${humanPost.id}`)
    assert(humanDetail.isAiGenerated === false, `真人帖 isAiGenerated 应为 false，实际 ${humanDetail.isAiGenerated}`)
    assert(humanDetail.authorIsAgent === false, `真人帖 authorIsAgent 应为 false，实际 ${humanDetail.authorIsAgent}`)

    // 2) 帖子列表（列表是主路径，与详情共用映射但必须一起验）
    const list = await api('GET', `/channels/${channel.id}/posts?limit=20`)
    const listedAgent = (list.items ?? []).find((item) => item.id === postId)
    const listedHuman = (list.items ?? []).find((item) => item.id === humanPost.id)
    assert(listedAgent, '频道列表应包含代理帖')
    assert(listedHuman, '频道列表应包含真人帖')
    assert(
      listedAgent.isAiGenerated === true && listedAgent.authorIsAgent === true,
      `列表里的代理帖应带 AI 标识，实际 ${listedAgent.isAiGenerated}/${listedAgent.authorIsAgent}`,
    )
    assert(
      listedHuman.isAiGenerated === false && listedHuman.authorIsAgent === false,
      `列表里的真人帖不应带 AI 标识，实际 ${listedHuman.isAiGenerated}/${listedHuman.authorIsAgent}`,
    )

    // 3) 评论列表
    const comments = await api('GET', `/posts/${postId}/comments?limit=50`)
    const agentComment = (comments.items ?? []).find((item) => item.id === commentId)
    const humanOne = (comments.items ?? []).find((item) => item.id === humanComment.id)
    assert(agentComment, '评论列表应包含代理评论')
    assert(humanOne, '评论列表应包含真人评论')
    assert(
      agentComment.isAiGenerated === true && agentComment.authorIsAgent === true,
      `代理评论应带 AI 标识，实际 ${agentComment.isAiGenerated}/${agentComment.authorIsAgent}`,
    )
    assert(
      humanOne.isAiGenerated === false && humanOne.authorIsAgent === false,
      `真人评论不应带 AI 标识，实际 ${humanOne.isAiGenerated}/${humanOne.authorIsAgent}`,
    )

    // 4) 作者信息：公开资料（代理 true）与「当前用户」（真人 false）
    // 注意：当前用户是 GET /auth/me；/users/me 不存在（会被 /users/{id} 当成 id="me" 返回 404）
    const agentPublic = await api('GET', `/users/${agent.agentId}`)
    assert(agentPublic.isAgent === true, `代理公开资料 isAgent 应为 true，实际 ${agentPublic.isAgent}`)
    const me = await api('GET', '/auth/me', { token: owner.token })
    assert(me.isAgent === false, `真人 /auth/me 的 isAgent 应为 false，实际 ${me.isAgent}`)
  })

  await step('日发帖上限：postsPerDay=1 后第二次发帖被闸门拦下（GUARDED）', async () => {
    const detail = await api('GET', `/admin/agents/${agent.agentId}`, { token: admin.token })
    assert(detail.todayPosts >= 1, `今日产出应 ≥1，实际 ${detail.todayPosts}`)

    // 人设改为每天只能发 1 帖（已经发过 1 帖）
    await api('PUT', `/admin/agents/${agent.agentId}`, {
      token: admin.token,
      body: agentBody({ channels: [channel.id], displayName: detail.displayName, postsPerDay: 1 }),
    })
    await queuePlans([
      {
        action: 'create_post',
        args: { channelId: channel.id, title: `超限帖 ${suffix()}`, content: '这条不应被发出。' },
      },
    ])
    const res = await runNow(admin.token, agent.agentId)
    assert(res.status === 'GUARDED', `超限应记 GUARDED，实际 ${res.status} ${res.reason ?? ''}`)
    assert(
      (res.reason ?? '').includes('今日发帖已达上限'),
      `拦截理由应说明日上限，实际「${res.reason}」`,
    )

    // 还原为 2，供后续去重用例使用
    await api('PUT', `/admin/agents/${agent.agentId}`, {
      token: admin.token,
      body: agentBody({ channels: [channel.id], displayName: detail.displayName, postsPerDay: 2 }),
    })
  })

  await step('同话题 7 天去重：同标题第二次发帖被拦下（GUARDED）', async () => {
    // 发帖间隔闸门（≥2 小时）排在去重之前，先把本次测试的运行时间回拨 3 小时，
    // 否则拦下理由会是「距上次发帖不足 2 小时」，测不到去重本身。
    dbExec(
      `UPDATE agent_runs SET started_at = DATE_SUB(started_at, INTERVAL 3 HOUR)
       WHERE agent_id='${agent.agentId}' AND output_type='POST' AND status='SUCCESS'`,
    )

    await queuePlans([
      {
        action: 'create_post',
        args: { channelId: channel.id, title: postTitle, content: `${postTitle} 换个说法再讲一次。` },
      },
    ])
    const res = await runNow(admin.token, agent.agentId)
    assert(res.status === 'GUARDED', `同话题重复应记 GUARDED，实际 ${res.status} ${res.reason ?? ''}`)
    assert(
      (res.reason ?? '').includes('重复'),
      `拦截理由应说明话题重复，实际「${res.reason}」`,
    )
  })

  await step('暂停后 run-now 被拒（返回 SKIPPED，不执行任何动作）', async () => {
    const paused = await api('POST', `/admin/agents/${agent.agentId}/pause`, { token: admin.token })
    assert(paused.status === 'PAUSED', `暂停后状态应为 PAUSED，实际 ${paused.status}`)

    await queuePlans([{ action: 'none', args: {} }])
    const res = await runNow(admin.token, agent.agentId)
    assert(
      res.status === 'SKIPPED',
      `暂停后 run-now 应返回 SKIPPED（或 4xx），实际 ${res.status}${res.run ? `（产出了 ${res.run.outputType}）` : ''}`,
    )
    assert((res.reason ?? '').length > 0, '跳过时应给出原因')

    await api('POST', `/admin/agents/${agent.agentId}/resume`, { token: admin.token })
  })

  await step('总开关关闭：run-now 返回 SKIPPED 且不写运行日志', async () => {
    await setAgentEnabled(admin.token, false)
    const before = (await listRuns(admin.token, agent.agentId)).total
    const res = await runNow(admin.token, agent.agentId)
    assert(res.status === 'SKIPPED', `总开关关闭后应 SKIPPED，实际 ${res.status}`)
    assert((res.reason ?? '').includes('总开关'), `理由应说明总开关，实际「${res.reason}」`)
    assert(res.run === null, '总开关关闭时不应产生运行日志行')

    const after = (await listRuns(admin.token, agent.agentId)).total
    assert(after === before, `运行日志条数不应变化：${before} → ${after}`)

    await setAgentEnabled(admin.token, true)
  })

  await step('无可用模型：run-now 记 SKIPPED（不抛异常、不记 FAILED）', async () => {
    // 把所有启用中的模型临时关掉，构造「无可用模型」
    const models = await api('GET', '/admin/ai/models', { token: admin.token })
    const enabled = (models.items ?? []).filter((item) => item.enabled)
    assert(enabled.length > 0, '运行前应有启用中的模型')
    const snapshot = enabled.map((item) => ({ id: item.id, request: toModelRequest(item) }))
    try {
      for (const item of enabled) {
        await api('PUT', `/admin/ai/models/${item.id}`, {
          token: admin.token,
          body: toModelRequest(item, { enabled: false }),
        })
      }
      await queuePlans([{ action: 'none', args: {} }])
      const res = await runNow(admin.token, agent.agentId)
      assert(
        res.status === 'SKIPPED',
        `无可用模型时应 SKIPPED（docs/12 §2.1 降级要求），实际 ${res.status} ${res.reason ?? ''}`,
      )
      assert(
        (res.reason ?? '').includes('模型'),
        `跳过理由应说明模型缺失，实际「${res.reason}」`,
      )
    } finally {
      for (const item of snapshot) {
        await api('PUT', `/admin/ai/models/${item.id}`, {
          token: admin.token,
          body: { ...item.request, enabled: true },
        })
      }
    }
  })

  await step('agent_runs 可回放：计划、工具调用、产出、token 都在', async () => {
    const page = await listRuns(admin.token, agent.agentId, 50)
    assert((page.items ?? []).length >= 3, `运行日志应有多条，实际 ${page.items?.length}`)

    const success = (page.items ?? []).find((item) => item.outputType === 'POST' && item.status === 'SUCCESS')
    assert(success, '应有成功的发帖运行日志')
    assert(success.outputId === postId, `日志产出 id 应为 ${postId}，实际 ${success.outputId}`)
    assert(success.planJson && success.planJson.includes('create_post'), '日志应保留模型原始计划（planJson）')
    assert(
      success.toolCallsJson && success.toolCallsJson.includes('create_post') && success.toolCallsJson.includes(postId),
      `日志应保留工具调用与产出（toolCallsJson），实际 ${success.toolCallsJson}`,
    )
    assert(success.tokensIn > 0 && success.tokensOut > 0, '日志应记录 token 消耗')
    assert(success.finishedAt, '日志应有结束时间')
  })
}

// ---------------------------------------------------------------------------
// B. 调度模式
// ---------------------------------------------------------------------------

async function tickFlow(admin) {
  const { channel } = await prepare(admin)
  if (!channel) return

  let agent

  await step('准备：开启总开关并创建活跃代理（全天可活跃）', async () => {
    await setAgentEnabled(admin.token, true)
    agent = await createAgent(admin, channel.id, { postsPerDay: 1, commentsPerDay: 1 })
    assert(agent.status === 'ACTIVE', '代理应为 ACTIVE')
  })

  await step('tick 会挑选活跃代理并写运行日志', async () => {
    const started = Date.now()
    let runs = 0
    while (Date.now() - started < TICK_WAIT_MS) {
      await sleep(5000)
      runs = (await listRuns(admin.token, agent.agentId)).total
      if (runs > 0) break
    }
    if (runs === 0) {
      // 概率性调度（0.3/tick）没命中不算失败，但要让报告里看得见
      throw new SkipCase(
        `${TICK_WAIT_MS / 1000} 秒内未被 tick 选中（每次 tick 概率 0.3），无法证明「暂停后不再挑选」有对照组；` +
          '可加大 AGENT_TICK_WAIT_MS 后重跑',
      )
    }
    const page = await listRuns(admin.token, agent.agentId)
    assert((page.items ?? []).length > 0, '运行日志应有条目')
    console.log(`      首次被 tick 选中耗时 ${Math.round((Date.now() - started) / 1000)}s`)
  })

  await step('暂停后调度不再挑选（观察窗口内无新运行日志）', async () => {
    await api('POST', `/admin/agents/${agent.agentId}/pause`, { token: admin.token })
    const before = (await listRuns(admin.token, agent.agentId)).total
    await sleep(TICK_QUIET_MS)
    const after = (await listRuns(admin.token, agent.agentId)).total
    assert(after === before, `暂停后不应再有 tick 产出：${before} → ${after}`)
  })

  await step('总开关关闭后全站停止（观察窗口内无新运行日志）', async () => {
    await api('POST', `/admin/agents/${agent.agentId}/resume`, { token: admin.token })
    await setAgentEnabled(admin.token, false)
    const before = (await listRuns(admin.token, agent.agentId)).total
    await sleep(TICK_QUIET_MS)
    const after = (await listRuns(admin.token, agent.agentId)).total
    assert(after === before, `总开关关闭后不应再有 tick 产出：${before} → ${after}`)
  })
}

// ---------------------------------------------------------------------------

async function main() {
  console.log(`\nAI 代理回归（${TICK_MODE ? '调度模式' : '主链路模式'}）`)
  const login = await api('POST', '/auth/login', {
    body: { username: ADMIN_USERNAME, password: ADMIN_PASSWORD },
  })
  const admin = { token: login.accessToken, id: login.user.id }
  cleanup.adminToken = admin.token

  const me = await api('GET', '/admin/me', { token: admin.token })
  assert(me.admin === true, '需要平台管理员账号（ADMIN_USERNAME / ADMIN_PASSWORD）')

  await clearPlans()
  if (TICK_MODE) {
    await tickFlow(admin)
  } else {
    await mainFlow(admin)
  }

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
