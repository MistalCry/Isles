#!/usr/bin/env node
/**
 * 内容与协作功能审计（docs/03 FR-POST/FR-CMT/FR-ITR/FR-ROLE/FR-BOARD/FR-SEARCH 等）。
 *
 * 覆盖 api-e2e（正向旅程）与 permissions-audit（越权边界）之外的细节：
 * 用户卡片、帖子/评论删除、板块 CRUD 与排序、身份组层级保护、频道内搜索、
 * 分享计数、置顶/精华筛选、通知过滤、私信分享帖子。
 *
 * 运行：node tools/e2e/feature-audit.mjs
 */

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1'
const results = []

function assert(condition, message) {
  if (!condition) throw new Error(`断言失败：${message}`)
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
    // ignore
  }
  if (!expect.includes(response.status)) {
    throw new Error(`期望 ${expect} 实际 ${response.status} ${method} ${path} → ${text.slice(0, 160)}`)
  }
  return data
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
  return { id: login.user.id, username, token: login.accessToken }
}

async function main() {
  console.log(`\n群岛功能细节审计 · ${BASE_URL}\n`)

  const owner = await newUser('fown')
  const member = await newUser('fmem')
  const channel = await api('POST', '/channels', {
    token: owner.token,
    expect: [201],
    body: { name: `Feature ${suffix()}`, avatarUrl: '/uploads/default-avatar.png', type: 'PUBLIC' },
  })
  await api('POST', `/channels/${channel.id}/join`, { token: member.token })
  const boards = await api('GET', `/channels/${channel.id}/boards`)
  const defaultBoard = boards.items[0]

  let post
  await step('发帖 / 分享计数 / 精华筛选', async () => {
    post = await api('POST', `/channels/${channel.id}/posts`, {
      token: owner.token,
      expect: [201],
      body: {
        boardId: defaultBoard.id,
        title: 'Feature post',
        content: 'content for feature audit vrchat',
        contentFormat: 'PLAIN',
      },
    })
    const shared = await api('POST', `/posts/${post.id}/share`, { token: member.token })
    assert(shared.shareCount === 1, '分享计数应为 1')
    await api('POST', `/channels/${channel.id}/posts/${post.id}/essence`, { token: owner.token })
    const essence = await api('GET', `/channels/${channel.id}/posts?tab=essence`)
    assert(essence.items.some((item) => item.id === post.id), '精华 tab 应包含加精帖子')
    const latest = await api('GET', `/channels/${channel.id}/posts?tab=latest`)
    assert(latest.items.some((item) => item.id === post.id), '全部 tab 应包含帖子')
  })

  await step('用户卡片：带频道上下文返回加入时间与身份组', async () => {
    const card = await api('GET', `/users/${member.id}/card?channelId=${channel.id}`)
    assert(card.id === member.id, '卡片应返回目标用户')
    assert(card.joinedAt, '应返回加入时间')
    assert(card.roleName, '应返回身份组名称')
  })

  await step('评论：发表 / 楼中楼回复 / 作者删除', async () => {
    const comment = await api('POST', `/posts/${post.id}/comments`, {
      token: member.token,
      expect: [201],
      body: { content: 'first comment' },
    })
    const reply = await api('POST', `/posts/${post.id}/comments`, {
      token: owner.token,
      expect: [201],
      body: { content: 'reply to comment', parentId: comment.id, replyToUserId: member.id },
    })
    assert(reply.parentId === comment.id, '回复应挂在父评论下')
    const list = await api('GET', `/posts/${post.id}/comments`)
    const parent = list.items.find((item) => item.id === comment.id)
    assert(parent?.replies?.length >= 1, '父评论应包含回复')
    await api('DELETE', `/comments/${reply.id}`, { token: owner.token, expect: [204] })
    const after = await api('GET', `/posts/${post.id}/comments`)
    const parentAfter = after.items.find((item) => item.id === comment.id)
    assert((parentAfter?.replies?.length ?? 0) === 0, '删除后回复应消失')
  })

  await step('板块：创建 / 重命名 / 排序 / 删除（至少保留一个）', async () => {
    const extra = await api('POST', `/channels/${channel.id}/boards`, {
      token: owner.token,
      expect: [201],
      body: { name: 'Feature Board', description: 'audit', sort: 1 },
    })
    const renamed = await api('PATCH', `/channels/${channel.id}/boards/${extra.id}`, {
      token: owner.token,
      body: { name: 'Feature Board Renamed' },
    })
    assert(renamed.name === 'Feature Board Renamed', '板块应重命名成功')
    const manageBoards = (await api('GET', `/channels/${channel.id}/boards/manage`, { token: owner.token })).items
    const ids = manageBoards.map((item) => item.id)
    const reordered = [extra.id, ...ids.filter((id) => id !== extra.id)]
    await api('PATCH', `/channels/${channel.id}/boards/reorder`, {
      token: owner.token,
      body: { boardIds: reordered },
    })
    const afterReorder = await api('GET', `/channels/${channel.id}/boards`)
    assert(afterReorder.items[0].id === extra.id, '排序后首个板块应为指定板块')
    await api('DELETE', `/channels/${channel.id}/boards/${extra.id}`, { token: owner.token, expect: [204] })
    await api('DELETE', `/channels/${channel.id}/boards/${defaultBoard.id}`, {
      token: owner.token,
      expect: [422],
    })
  })

  await step('身份组：系统组不可删；不能授予超过自身的权限', async () => {
    const roles = (await api('GET', `/channels/${channel.id}/roles`, { token: owner.token })).items
    const ownerRole = roles.find((role) => role.system && role.name.includes('频道主'))
    assert(ownerRole, '应存在频道主系统身份组')
    await api('DELETE', `/channels/${channel.id}/roles/${ownerRole.id}`, {
      token: owner.token,
      expect: [403],
    })
    // 给成员一个只有「发帖」的自定义身份组，再让他尝试创建含 channel.dissolve 的身份组
    const limited = await api('POST', `/channels/${channel.id}/roles`, {
      token: owner.token,
      expect: [201],
      body: { name: `Limited ${suffix()}`, permissions: ['post.create'] },
    })
    await api('POST', `/channels/${channel.id}/members/${member.id}/role`, {
      token: owner.token,
      body: { roleId: limited.id },
    })
    await api('POST', `/channels/${channel.id}/roles`, {
      token: member.token,
      expect: [403],
      body: { name: `Escalate ${suffix()}`, permissions: ['channel.dissolve'] },
    })
    // 恢复为普通成员身份组，避免影响后续「评论触发通知」场景
    const memberRole = roles.find((role) => role.system && role.name.includes('普通成员'))
    if (memberRole) {
      await api('POST', `/channels/${channel.id}/members/${member.id}/role`, {
        token: owner.token,
        body: { roleId: memberRole.id },
      })
    }
  })

  await step('频道内搜索：只返回本频道内容', async () => {
    const other = await api('POST', '/channels', {
      token: owner.token,
      expect: [201],
      body: { name: `Other ${suffix()}`, avatarUrl: '/uploads/default-avatar.png', type: 'PUBLIC' },
    })
    const otherBoards = await api('GET', `/channels/${other.id}/boards`)
    const otherPost = await api('POST', `/channels/${other.id}/posts`, {
      token: owner.token,
      expect: [201],
      body: { boardId: otherBoards.items[0].id, content: 'unique-other-marker', contentFormat: 'PLAIN' },
    })
    const scoped = await api('GET', `/channels/${channel.id}/search?q=unique-other-marker`)
    assert(!scoped.items.some((item) => item.id === otherPost.id), '频道内搜索不应返回其他频道帖子')
    const global = await api('GET', '/search?q=unique-other-marker')
    assert(global.items.some((item) => item.id === otherPost.id), '全站搜索应能搜到该帖子')
  })

  await step('通知：按频道与分类过滤', async () => {
    // member 评论 owner 的帖子 → owner 收到互动通知
    await api('POST', `/posts/${post.id}/comments`, {
      token: member.token,
      expect: [201],
      body: { content: 'notify owner' },
    })
    const byChannel = await api('GET', `/notifications?channelId=${channel.id}&limit=10`, {
      token: owner.token,
    })
    assert(byChannel.items.length >= 1, '按频道过滤应返回通知')
    assert(
      byChannel.items.every((item) => item.channelId === channel.id),
      '过滤结果应都属于该频道',
    )
    const interaction = await api('GET', `/notifications?category=INTERACTION&limit=10`, {
      token: owner.token,
    })
    assert(
      interaction.items.every((item) => item.category === 'INTERACTION'),
      '分类过滤应只返回互动通知',
    )
  })

  await step('私信分享帖子：消息含帖子卡片信息', async () => {
    const conversation = await api('POST', '/dm/conversations', {
      token: owner.token,
      expect: [200, 201],
      body: { userId: member.id },
    })
    const message = await api('POST', `/dm/conversations/${conversation.id}/messages`, {
      token: owner.token,
      expect: [201],
      body: { type: 'POST', postId: post.id },
    })
    assert(message.type === 'POST', '消息类型应为 POST')
    assert(message.post?.postId === post.id, '消息应携带帖子分享卡片（postId）')
    assert(message.post?.title?.includes('Feature post'), '卡片应包含帖子标题')
  })

  await step('作者删除自己的帖子；普通成员不可删除他人帖子', async () => {
    const bystander = await newUser('fbys')
    await api('POST', `/channels/${channel.id}/join`, { token: bystander.token })
    const ownPost = await api('POST', `/channels/${channel.id}/posts`, {
      token: member.token,
      expect: [201],
      body: { boardId: defaultBoard.id, content: 'member own post', contentFormat: 'PLAIN' },
    })
    // 无 post.delete 权限的普通成员不能删除他人帖子（频道主属管理员，允许删除）
    await api('DELETE', `/posts/${ownPost.id}`, { token: bystander.token, expect: [403] })
    await api('DELETE', `/posts/${ownPost.id}`, { token: member.token, expect: [204] })
    await api('GET', `/posts/${ownPost.id}`, { expect: [404] })
  })

  const passed = results.filter((item) => item.ok).length
  console.log(`\n结果：${passed}/${results.length} 步通过\n`)
  if (passed !== results.length) process.exit(1)
}

main().catch((error) => {
  console.error(`\n功能审计异常：${error.message}\n`)
  process.exit(1)
})
