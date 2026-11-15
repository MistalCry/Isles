#!/usr/bin/env node
/**
 * 群岛（Isles）API 端到端冒烟（docs/08 §5 场景 1/3/4/5/8 的接口层实现）。
 *
 * 覆盖核心用户旅程：注册/登录 → 建频道/发帖/评论/点赞 → 搜索/热门 →
 * 私密频道邀请加入 → 好友/私信 → 通知 → 管理端（成员/日志/数据/举报）→ AI 状态。
 *
 * 运行：先启动后端（默认 8080），再执行
 *   node tools/e2e/api-e2e.mjs
 *   BASE_URL=http://127.0.0.1:8082/api/v1 node tools/e2e/api-e2e.mjs
 *
 * 说明：脚本只通过公开 HTTP 契约验证，不直接读写数据库；每次运行创建带时间戳的新用户，
 * 不清理数据（dev 库），便于失败后人工复查。
 */

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1'

const results = []
let token = ''

class HttpError extends Error {
  constructor(status, body, path) {
    super(`${status} ${path}: ${typeof body === 'string' ? body : JSON.stringify(body)}`)
    this.status = status
    this.body = body
  }
}

async function call(method, path, { body, auth = true, expect } = {}) {
  const headers = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (auth && token) headers.Authorization = `Bearer ${token}`
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
    // 非 JSON（SSE/纯文本）：保留原始文本
  }
  const allowed = expect ?? [200]
  if (!allowed.includes(response.status)) {
    throw new HttpError(response.status, data, `${method} ${path}`)
  }
  return { status: response.status, data, headers: response.headers }
}

function assert(condition, message) {
  if (!condition) throw new Error(`断言失败：${message}`)
}

async function step(name, fn) {
  const started = Date.now()
  try {
    await fn()
    results.push({ name, ok: true, ms: Date.now() - started })
    console.log(`  ✓ ${name} (${Date.now() - started}ms)`)
  } catch (error) {
    results.push({ name, ok: false, ms: Date.now() - started, error: error.message })
    console.error(`  ✗ ${name}\n    ${error.message}`)
    throw error
  }
}

function suffix() {
  return `${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`
}

async function registerAndLogin(prefix) {
  const username = `${prefix}_${suffix()}`
  const password = 'Passw0rd123'
  await call('POST', '/auth/register', {
    auth: false,
    expect: [201],
    body: { username, password, nickname: `${prefix} 测试` },
  })
  const login = await call('POST', '/auth/login', { auth: false, body: { username, password } })
  assert(login.data?.accessToken, '登录应返回 accessToken')
  return { username, password, userId: login.data.user.id, token: login.data.accessToken }
}

async function main() {
  console.log(`\n群岛 API E2E · ${BASE_URL}\n`)

  const health = await fetch(`${BASE_URL.replace(/\/api\/v1$/, '')}/actuator/health`).catch(() => null)
  if (!health || !health.ok) {
    console.error('后端不可用：请先启动后端（mvn spring-boot:run）或设置 BASE_URL')
    process.exit(1)
  }

  let alice
  let bob

  await step('注册并登录两个用户', async () => {
    alice = await registerAndLogin('alice')
    bob = await registerAndLogin('bob')
    token = alice.token
  })

  let publicChannel
  let boardId
  let postId
  await step('创建公开频道并读取默认板块', async () => {
    const created = await call('POST', '/channels', {
      expect: [201],
      body: { name: `E2E 公开频道 ${suffix()}`, avatarUrl: '/uploads/default-avatar.png', type: 'PUBLIC' },
    })
    publicChannel = created.data
    assert(publicChannel.channelNo, '频道应生成频道号')
    assert(publicChannel.joined === true, '创建者应自动成为成员')
    const boards = await call('GET', `/channels/${publicChannel.id}/boards`, { auth: false })
    boardId = boards.data.items?.[0]?.id
    assert(boardId, '应自动生成默认板块')
  })

  await step('发帖 / 评论 / 点赞', async () => {
    const post = await call('POST', `/channels/${publicChannel.id}/posts`, {
      expect: [201],
      body: {
        boardId,
        title: 'E2E 帖子',
        content: '群岛端到端冒烟：vrchat 活动合集',
        contentFormat: 'PLAIN',
      },
    })
    postId = post.data.id
    const comment = await call('POST', `/posts/${postId}/comments`, {
      expect: [201],
      body: { content: 'E2E 评论' },
    })
    assert(comment.data.id, '评论应创建成功')
    const like = await call('POST', `/posts/${postId}/like`)
    assert(like.data.liked === true && like.data.likeCount >= 1, '点赞应生效')
    const detail = await call('GET', `/posts/${postId}`, { auth: false })
    assert(detail.data.commentCount >= 1, '帖子详情应包含评论计数')
  })

  await step('搜索与热门', async () => {
    const search = await call('GET', '/search?q=E2E', { auth: false })
    assert(Array.isArray(search.data.items), '搜索应返回 items')
    assert(search.data.items.some((item) => item.id === postId), '搜索结果应命中刚发的帖子')
    const hot = await call('GET', '/hot?limit=5', { auth: false })
    assert(Array.isArray(hot.data.items), '热门应返回 items')
    const suggest = await call('GET', '/search/suggest?q=E2E')
    assert(suggest.data, '联想接口应返回结果')
  })

  await step('私密频道邀请链接 + 加入申请审批', async () => {
    const created = await call('POST', '/channels', {
      expect: [201],
      body: {
        name: `E2E 私密频道 ${suffix()}`,
        avatarUrl: '/uploads/default-avatar.png',
        type: 'PRIVATE',
        visibility: 'MEMBERS_ONLY',
      },
    })
    const privateChannel = created.data
    const invite = await call('POST', `/channels/${privateChannel.id}/invites`, {
      expect: [201],
      body: { requiresApproval: true, maxUses: 5 },
    })
    assert(invite.data.token, '邀请链接应返回明文 token')

    const bobToken = bob.token
    token = bobToken
    const accepted = await call('POST', `/invites/${invite.data.token}/accept`)
    assert(accepted.data.status === 'PENDING', '需审批的邀请应生成待审批申请')

    token = alice.token
    const requests = await call('GET', `/channels/${privateChannel.id}/join-requests?status=PENDING`)
    const request = requests.data.items?.find((item) => item.userId === bob.userId)
    assert(request, '频道主应看到待审批申请')
    await call('POST', `/channels/${privateChannel.id}/join-requests/${request.id}/approve`, { expect: [200, 204] })
    const members = await call('GET', `/channels/${privateChannel.id}/members?page=1&size=20`)
    assert(
      members.data.items?.some((item) => item.userId === bob.userId),
      '审批通过后成员列表应包含申请人',
    )
  })

  await step('好友申请 → 同意 → 私信', async () => {
    token = alice.token
    const requested = await call('POST', '/friends/requests', {
      expect: [200, 201],
      body: { userId: bob.userId, message: 'E2E 好友申请' },
    })
    token = bob.token
    const incoming = await call('GET', '/friends/requests?direction=incoming')
    const request = incoming.data.items?.find((item) => item.id === requested.data.id)
    assert(request, '被申请人应看到好友申请')
    await call('POST', `/friends/requests/${request.id}/accept`, { expect: [200, 204] })
    const friends = await call('GET', '/friends')
    assert(friends.data.items?.some((item) => item.userId === alice.userId), '好友列表应包含对方')

    const conversation = await call('POST', '/dm/conversations', {
      expect: [200, 201],
      body: { userId: alice.userId },
    })
    assert(conversation.data.id, '应创建私信会话')
    await call('POST', `/dm/conversations/${conversation.data.id}/messages`, {
      expect: [201],
      body: { type: 'TEXT', content: 'E2E 私信消息' },
    })
    token = alice.token
    const messages = await call('GET', `/dm/conversations/${conversation.data.id}/messages`)
    assert(
      messages.data.items?.some((item) => item.content === 'E2E 私信消息'),
      '对方应能读到私信消息',
    )
    const unread = await call('GET', '/dm/unread-count')
    assert(typeof unread.data.total === 'number', '私信未读数应为数字')
  })

  await step('通知（互动通知 + 未读计数 + 已读）', async () => {
    token = alice.token
    const list = await call('GET', '/notifications?limit=10')
    assert(Array.isArray(list.data.items), '通知列表应返回 items')
    const unread = await call('GET', '/notifications/unread-count')
    assert(typeof unread.data.unreadCount === 'number', '通知未读总数应为数字')
    await call('POST', '/notifications/read-all', { expect: [204] })
    const after = await call('GET', '/notifications/unread-count')
    assert(after.data.unreadCount === 0, '全部已读后未读数应为 0')
  })

  await step('管理端：数据中心 / 管理日志 / 内容管理', async () => {
    token = alice.token
    const overview = await call('GET', `/channels/${publicChannel.id}/stats/overview`)
    assert(overview.data.memberCount >= 1, '概览应包含成员数')
    const trends = await call('GET', `/channels/${publicChannel.id}/stats/trends?days=7`)
    assert(Array.isArray(trends.data), '趋势应为数组')
    const content = await call('GET', `/channels/${publicChannel.id}/stats/content`)
    assert(Array.isArray(content.data.boardDistribution), '内容分析应包含板块分布')
    const members = await call('GET', `/channels/${publicChannel.id}/stats/members?days=7`)
    assert(Array.isArray(members.data.roleDistribution), '成员分析应包含身份组分布')

    const logs = await call('GET', `/channels/${publicChannel.id}/audit-logs?page=1&size=20`)
    assert(Array.isArray(logs.data.items) && logs.data.total >= 1, '管理日志应有记录')

    const managePosts = await call('GET', `/channels/${publicChannel.id}/posts/manage?page=1&size=10`)
    assert(managePosts.data.items?.some((item) => item.id === postId), '内容管理应能看到帖子')

    await call('POST', `/channels/${publicChannel.id}/posts/${postId}/pin`)
    await call('POST', `/channels/${publicChannel.id}/posts/${postId}/essence`)
    const pinned = await call('GET', `/posts/${postId}`, { auth: false })
    assert(pinned.data.pinned === true && pinned.data.essence === true, '置顶/精华应生效')
  })

  await step('举报 → 频道处置', async () => {
    token = bob.token
    await call('POST', '/reports', {
      expect: [201],
      body: { targetType: 'POST', targetId: postId, reason: 'SPAM', description: 'E2E 举报' },
    })
    token = alice.token
    const reports = await call('GET', `/channels/${publicChannel.id}/reports?status=PENDING&page=1&size=20`)
    const report = reports.data.items?.find((item) => item.targetId === postId)
    assert(report, '频道管理应看到举报')
    const handled = await call('POST', `/channels/${publicChannel.id}/reports/${report.id}/handle`, {
      body: { status: 'ACCEPTED', action: 'NONE', note: 'E2E 处置' },
    })
    assert(handled.data.status === 'ACCEPTED', '举报应变为已通过')
  })

  await step('AI 状态与频道助手设置读取', async () => {
    token = alice.token
    const status = await call('GET', '/ai/status')
    assert(typeof status.data.configured === 'boolean', 'AI 状态应返回 configured')
    const settings = await call('GET', `/channels/${publicChannel.id}/ai/settings`)
    assert(typeof settings.data.enabled === 'boolean', '助手设置应返回开关状态')
  })

  // 已配置模型（LMStudio / mock-openai）时验证流式链路；未配置时跳过并保留状态断言。
  const aiStatus = await call('GET', '/ai/status')
  if (aiStatus.data.configured) {
    await step('频道问问：流式回答落库', async () => {
      token = alice.token
      const conversation = await call('POST', '/ai/conversations', {
        expect: [201],
        body: { scope: 'GLOBAL', title: 'E2E AI' },
      })
      const response = await fetch(
        `${BASE_URL}/ai/conversations/${conversation.data.id}/messages/stream`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'text/event-stream',
            Authorization: `Bearer ${token}`,
          },
          body: JSON.stringify({ content: '社区里最近在讨论什么？', imageUrls: [], deepThinking: false }),
        },
      )
      assert(response.ok, `流式接口应返回 200，实际 ${response.status}`)
      const stream = await response.text()
      assert(stream.includes('event:delta') || stream.includes('event:done'), 'SSE 应包含 delta/done 事件')
      const messages = await call('GET', `/ai/conversations/${conversation.data.id}/messages`)
      assert((messages.data.items?.length ?? 0) >= 2, '会话应落库提问与回答两条消息')
    })
  }

  // 限流验证会消耗 IP 配额（注册 5 次/小时），默认跳过；需要时 E2E_CHECK_RATE_LIMIT=1 单独运行。
  if (process.env.E2E_CHECK_RATE_LIMIT === '1') await step('限流：登录失败超限返回 429 + Retry-After', async () => {
    const probe = { username: `ratelimit_${suffix()}`, password: 'WrongPass123' }
    let limited = false
    for (let i = 0; i < 14; i += 1) {
      const response = await fetch(`${BASE_URL}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(probe),
      })
      if (response.status === 429) {
        limited = true
        assert(response.headers.get('retry-after'), '429 应带 Retry-After 头')
        break
      }
    }
    assert(limited, '连续登录失败应触发限流（检查 app.rate-limit.enabled）')
  })

  const passed = results.filter((item) => item.ok).length
  console.log(`\n结果：${passed}/${results.length} 步通过\n`)
}

main().catch((error) => {
  console.error(`\nE2E 失败：${error.message}\n`)
  process.exit(1)
})
