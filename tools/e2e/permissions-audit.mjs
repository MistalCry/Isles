#!/usr/bin/env node
/**
 * 权限与边界审计（docs/08 §2「必测场景」的自动化补充）。
 *
 * 与 api-e2e.mjs 的正向旅程互补，本脚本专注「越权/边界/状态机」：
 * 未加入发帖、禁言、全员禁言、退出、私密可见性、邀请审批、拉黑、解散、
 * 板块范围权限、重复举报、搜索通配符转义、媒体类型校验、数据聚合。
 *
 * 运行：node tools/e2e/permissions-audit.mjs
 * 说明：需要后端可访问；注册限流下请用 RATE_LIMIT_ENABLED=false 启动。
 */

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1'
const results = []

function assert(condition, message) {
  if (!condition) throw new Error(`断言失败：${message}`)
}

async function api(method, path, { token, body, expect = [200], raw = false } = {}) {
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
    // 保留原始文本
  }
  if (!expect.includes(response.status)) {
    throw new Error(`期望 ${expect} 实际 ${response.status} ${method} ${path} → ${text.slice(0, 200)}`)
  }
  return raw ? { status: response.status, data, response } : data
}

async function step(name, fn) {
  try {
    await fn()
    results.push({ name, ok: true })
    console.log(`  ✓ ${name}`)
  } catch (error) {
    results.push({ name, ok: false, error: error.message })
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
  return { username, password, id: login.user.id, token: login.accessToken }
}

async function createChannel(owner, { type = 'PUBLIC', visibility } = {}) {
  return api('POST', '/channels', {
    token: owner.token,
    expect: [201],
    body: {
      name: `Audit ${type} ${suffix()}`,
      avatarUrl: '/uploads/default-avatar.png',
      type,
      ...(visibility ? { visibility } : {}),
    },
  })
}

async function boardOf(channelId) {
  const boards = await api('GET', `/channels/${channelId}/boards`)
  return boards.items[0]
}

async function main() {
  console.log(`\n群岛权限与边界审计 · ${BASE_URL}\n`)

  const health = await fetch(`${BASE_URL.replace(/\/api\/v1$/, '')}/actuator/health`).catch(() => null)
  if (!health || !health.ok) {
    console.error('后端不可用，请先启动后端')
    process.exit(1)
  }

  let owner
  let member
  let outsider
  let channel
  let board

  await step('准备：三个用户 + 公开频道 + 成员加入', async () => {
    owner = await newUser('own')
    member = await newUser('mem')
    outsider = await newUser('out')
    channel = await createChannel(owner)
    board = await boardOf(channel.id)
    await api('POST', `/channels/${channel.id}/join`, { token: member.token })
    const joined = await api('GET', `/channels/${channel.id}`, { token: member.token })
    assert(joined.joined === true, '成员加入后 joined 应为 true')
  })

  await step('游客可读公开频道/帖子；未加入用户发帖被拒', async () => {
    await api('GET', `/channels/${channel.id}`)
    await api('GET', `/channels/${channel.id}/posts`)
    await api('POST', `/channels/${channel.id}/posts`, {
      token: outsider.token,
      expect: [403],
      body: { boardId: board.id, content: 'outsider should fail', contentFormat: 'PLAIN' },
    })
  })

  await step('个人禁言：禁言期间成员发帖 403，解除后恢复', async () => {
    await api('POST', `/channels/${channel.id}/members/${member.id}/mute`, {
      token: owner.token,
      body: { durationSeconds: 60 },
    })
    await api('POST', `/channels/${channel.id}/posts`, {
      token: member.token,
      expect: [403],
      body: { boardId: board.id, content: 'muted member post', contentFormat: 'PLAIN' },
    })
    await api('DELETE', `/channels/${channel.id}/members/${member.id}/mute`, { token: owner.token })
    await api('POST', `/channels/${channel.id}/posts`, {
      token: member.token,
      expect: [201],
      body: { boardId: board.id, content: 'member post after unmute', contentFormat: 'PLAIN' },
    })
  })

  await step('全员禁言：普通成员 403，频道主仍可发言，解除后恢复', async () => {
    await api('POST', `/channels/${channel.id}/mute-all`, {
      token: owner.token,
      body: { durationSeconds: 60 },
    })
    await api('POST', `/channels/${channel.id}/posts`, {
      token: member.token,
      expect: [403],
      body: { boardId: board.id, content: 'mute-all member post', contentFormat: 'PLAIN' },
    })
    await api('POST', `/channels/${channel.id}/posts`, {
      token: owner.token,
      expect: [201],
      body: { boardId: board.id, content: 'owner post during mute-all', contentFormat: 'PLAIN' },
    })
    await api('DELETE', `/channels/${channel.id}/mute-all`, { token: owner.token })
    await api('POST', `/channels/${channel.id}/posts`, {
      token: member.token,
      expect: [201],
      body: { boardId: board.id, content: 'member post after mute-all lifted', contentFormat: 'PLAIN' },
    })
  })

  await step('退出频道：成员数递减，退出后发帖 403；频道主不可退出', async () => {
    const before = await api('GET', `/channels/${channel.id}`)
    await api('POST', `/channels/${channel.id}/leave`, { token: member.token, expect: [204] })
    const after = await api('GET', `/channels/${channel.id}`)
    assert(after.memberCount === before.memberCount - 1, '退出后成员数应 -1')
    await api('POST', `/channels/${channel.id}/posts`, {
      token: member.token,
      expect: [403],
      body: { boardId: board.id, content: 'left member post', contentFormat: 'PLAIN' },
    })
    await api('POST', `/channels/${channel.id}/leave`, { token: owner.token, expect: [422] })
  })

  let privateChannel
  await step('私密频道：非成员 404；邀请链接 + 审批后可见', async () => {
    privateChannel = await createChannel(owner, { type: 'PRIVATE', visibility: 'MEMBERS_ONLY' })
    await api('GET', `/channels/${privateChannel.id}`, { token: outsider.token, expect: [404] })
    const invite = await api('POST', `/channels/${privateChannel.id}/invites`, {
      token: owner.token,
      expect: [201],
      body: { requiresApproval: true, maxUses: 3 },
    })
    const applied = await api('POST', `/invites/${invite.token}/accept`, { token: outsider.token })
    assert(applied.status === 'PENDING', '需审批的邀请应返回 PENDING')
    await api('POST', `/invites/${invite.token}/accept`, { token: outsider.token, expect: [409] })
    const requests = await api('GET', `/channels/${privateChannel.id}/join-requests?status=PENDING`, {
      token: owner.token,
    })
    const request = requests.items.find((item) => item.userId === outsider.id)
    assert(request, '频道主应看到加入申请')
    await api('POST', `/channels/${privateChannel.id}/join-requests/${request.id}/approve`, {
      token: owner.token,
      expect: [200, 204],
    })
    const visible = await api('GET', `/channels/${privateChannel.id}`, { token: outsider.token })
    assert(visible.joined === true, '审批通过后应可访问且 joined=true')
  })

  await step('拉黑：被拉黑方无法私信；解除后恢复', async () => {
    await api('POST', `/users/${member.id}/block`, { token: owner.token, expect: [204] })
    await api('POST', '/dm/conversations', {
      token: member.token,
      expect: [403],
      body: { userId: owner.id },
    })
    await api('DELETE', `/users/${member.id}/block`, { token: owner.token, expect: [204] })
    await api('POST', '/dm/conversations', {
      token: member.token,
      expect: [200, 201],
      body: { userId: owner.id },
    })
  })

  await step('板块范围权限：子板块管理员仅能管理绑定板块内容', async () => {
    // 上一场景成员已退出，这里重新加入以分配身份组
    await api('POST', `/channels/${channel.id}/join`, { token: member.token })
    const extraBoard = await api('POST', `/channels/${channel.id}/boards`, {
      token: owner.token,
      expect: [201],
      body: { name: `Audit Board ${suffix()}`, description: 'scope test' },
    })
    const postInExtra = await api('POST', `/channels/${channel.id}/posts`, {
      token: owner.token,
      expect: [201],
      body: { boardId: extraBoard.id, content: 'post in extra board', contentFormat: 'PLAIN' },
    })
    const postInDefault = await api('POST', `/channels/${channel.id}/posts`, {
      token: owner.token,
      expect: [201],
      body: { boardId: board.id, content: 'post in default board', contentFormat: 'PLAIN' },
    })
    const role = await api('POST', `/channels/${channel.id}/roles`, {
      token: owner.token,
      expect: [201],
      body: {
        name: `BoardAdmin ${suffix()}`,
        permissions: ['post.pin', 'post.essence', 'post.delete', 'comment.delete', 'channel.content.manage'],
        scopeBoardId: board.id,
      },
    })
    await api('POST', `/channels/${channel.id}/members/${member.id}/role`, {
      token: owner.token,
      body: { roleId: role.id },
    })
    // 绑定板块内可置顶
    await api('POST', `/channels/${channel.id}/posts/${postInDefault.id}/pin`, { token: member.token })
    // 其他板块应被拒
    await api('POST', `/channels/${channel.id}/posts/${postInExtra.id}/pin`, {
      token: member.token,
      expect: [403],
    })
  })

  await step('举报：首次 201，重复待处理 409；处置后状态变更', async () => {
    const post = await api('POST', `/channels/${channel.id}/posts`, {
      token: owner.token,
      expect: [201],
      body: { boardId: board.id, content: 'report target post', contentFormat: 'PLAIN' },
    })
    await api('POST', '/reports', {
      token: member.token,
      expect: [201],
      body: { targetType: 'POST', targetId: post.id, reason: 'SPAM' },
    })
    await api('POST', '/reports', {
      token: member.token,
      expect: [409],
      body: { targetType: 'POST', targetId: post.id, reason: 'SPAM' },
    })
    const reports = await api('GET', `/channels/${channel.id}/reports?status=PENDING&page=1&size=20`, {
      token: owner.token,
    })
    const report = reports.items.find((item) => item.targetId === post.id)
    const handled = await api('POST', `/channels/${channel.id}/reports/${report.id}/handle`, {
      token: owner.token,
      body: { status: 'REJECTED', action: 'NONE', note: 'audit' },
    })
    assert(handled.status === 'REJECTED', '举报处置后状态应为 REJECTED')
  })

  await step('搜索：通配符被转义（% 不应匹配全部）', async () => {
    const wildcard = await api('GET', '/search?q=%25')
    const normal = await api('GET', '/search?q=Audit')
    assert(Array.isArray(wildcard.items), '通配符搜索应正常返回')
    assert(
      wildcard.items.length <= normal.items.length,
      `通配符不应放大结果（wildcard=${wildcard.items.length}, normal=${normal.items.length}）`,
    )
  })

  await step('媒体：伪装成图片的文本被拒绝', async () => {
    const form = new FormData()
    form.append('file', new Blob(['not an image'], { type: 'image/png' }), 'fake.png')
    form.append('type', 'image')
    const response = await fetch(`${BASE_URL}/media/upload`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${owner.token}` },
      body: form,
    })
    assert([400, 415].includes(response.status), `非法文件应被拒绝，实际 ${response.status}`)
  })

  await step('数据中心：手动聚合返回指标，趋势可读', async () => {
    const stats = await api('POST', `/channels/${channel.id}/stats/aggregate`, { token: owner.token })
    assert(typeof stats.memberCount === 'number', '聚合结果应含 memberCount')
    const trends = await api('GET', `/channels/${channel.id}/stats/trends?days=7`, { token: owner.token })
    assert(Array.isArray(trends), '趋势应为数组')
  })

  await step('解散频道：之后访问 404，成员列表不可用', async () => {
    const temp = await createChannel(owner)
    await api('POST', `/channels/${temp.id}/dissolve`, { token: owner.token, expect: [204] })
    await api('GET', `/channels/${temp.id}`, { expect: [404] })
    await api('GET', `/channels/${temp.id}/members?page=1&size=10`, {
      token: owner.token,
      expect: [404],
    })
  })

  const passed = results.filter((item) => item.ok).length
  console.log(`\n结果：${passed}/${results.length} 步通过\n`)
  if (passed !== results.length) process.exit(1)
}

main().catch((error) => {
  console.error(`\n审计脚本异常：${error.message}\n`)
  process.exit(1)
})
