#!/usr/bin/env node
/**
 * 内容链路回归（第 13 轮）：浏览量去重 / 敏感词三模式 / 搜索缩略图。
 *
 * 1. 浏览量：作者自己刷新不计；同一账号 30 分钟内重复访问只计一次；换账号（换会话）再计一次。
 * 2. 敏感词三模式（全局开关 `content.sensitiveWord.mode`）：
 *    - REVIEW：内容照常入库（原文），只记录命中；
 *    - MASK：入库内容里命中词变成 `***`；
 *    - BLOCK：发布直接 422 `SENSITIVE_WORD_BLOCKED`。
 * 3. 搜索缩略图：搜索命中的帖子要带 `coverUrl`（显式封面）与 `media`（首图）。
 *
 * 敏感词用带时间戳的唯一词，模式切换只影响自己造的内容；跑完恢复原模式并删词。
 * 运行：BASE_URL=http://127.0.0.1:8082/api/v1 node tools/e2e/content-regression.mjs
 */

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1'
const ADMIN_USERNAME = process.env.ADMIN_USERNAME || 'admin_demo'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Passw0rd123'

const MODE_KEY = 'content.sensitiveWord.mode'

const results = []
const cleanup = {
  channelId: null,
  ownerToken: null,
  adminToken: null,
  wordId: null,
  originalMode: null,
  /** 运行前该键是否本来就有覆盖行：没有就 reset（不留行），有就还原原值 */
  originalModeOverridden: false,
  modeTouched: false,
}

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

async function setMode(mode) {
  cleanup.modeTouched = true
  await api('PUT', `/admin/config/${MODE_KEY}`, { token: cleanup.adminToken, body: { value: mode } })
}

/** 恢复敏感词模式：原本有覆盖值就还原，原本是默认值就 reset（不留行） */
async function restoreMode() {
  if (!cleanup.adminToken) return
  if (cleanup.originalModeOverridden) {
    await api('PUT', `/admin/config/${MODE_KEY}`, {
      token: cleanup.adminToken,
      body: { value: cleanup.originalMode ?? 'REVIEW' },
    })
    console.log(`  ✓ 敏感词模式已还原为 ${cleanup.originalMode}`)
  } else {
    await api('POST', `/admin/config/${MODE_KEY}/reset`, { token: cleanup.adminToken })
    console.log('  ✓ 敏感词模式已恢复默认（覆盖行已清除）')
  }
}

async function runCleanup() {
  console.log('\n清理：')
  if (cleanup.modeTouched) {
    try {
      await restoreMode()
    } catch (error) {
      console.error(`  ! 恢复敏感词模式失败：${error.message}`)
    }
  }
  if (cleanup.wordId && cleanup.adminToken) {
    try {
      await api('DELETE', `/admin/sensitive-words/${cleanup.wordId}`, {
        token: cleanup.adminToken,
        expect: [204, 404],
      })
      console.log('  ✓ 测试敏感词已删除')
    } catch (error) {
      console.error(`  ! 删除敏感词失败：${error.message}`)
    }
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
}

async function main() {
  console.log(`\n群岛内容链路回归 · ${BASE_URL}\n`)

  const health = await fetch(`${BASE_URL.replace(/\/api\/v1$/, '')}/actuator/health`).catch(() => null)
  if (!health || !health.ok) {
    console.error('后端不可用，请先启动后端')
    process.exit(1)
  }

  let admin
  let author
  let reader
  let otherReader
  let channel
  let board
  const word = `E2E敏感词${suffix()}`
  const searchKeyword = `E2E搜索词${suffix()}`

  await step('准备：超管 + 作者/读者/另一个读者 + 公开频道', async () => {
    const login = await api('POST', '/auth/login', { body: { username: ADMIN_USERNAME, password: ADMIN_PASSWORD } })
    admin = { token: login.accessToken }
    cleanup.adminToken = admin.token

    author = await newUser('cntauth')
    reader = await newUser('cntread')
    otherReader = await newUser('cntother')
    channel = await api('POST', '/channels', {
      token: author.token,
      expect: [201],
      body: { name: `Content Audit ${suffix()}`, avatarUrl: '/uploads/default-avatar.png', type: 'PUBLIC' },
    })
    cleanup.channelId = channel.id
    cleanup.ownerToken = author.token
    const boards = await api('GET', `/channels/${channel.id}/boards`)
    board = boards.items[0]
    await api('POST', `/channels/${channel.id}/join`, { token: reader.token })
    await api('POST', `/channels/${channel.id}/join`, { token: otherReader.token })
  })

  if (!author || !channel) {
    blockedReason = '前置准备失败（账号/频道未创建），常见原因：注册限流 429，请用 RATE_LIMIT_ENABLED=false 启动后端'
  }

  // ------------------------------------------------------------------
  // 一、浏览量去重
  // ------------------------------------------------------------------
  let viewPost

  await step('浏览量：作者刷新自己的帖子不计', async () => {
    viewPost = await api('POST', `/channels/${channel.id}/posts`, {
      token: author.token,
      expect: [201],
      body: { boardId: board.id, title: `浏览量探针 ${suffix()}`, content: '浏览量测试正文', contentFormat: 'PLAIN' },
    })
    assert((viewPost.viewCount ?? 0) === 0, `新帖浏览量应为 0，实际 ${viewPost.viewCount}`)

    await api('GET', `/posts/${viewPost.id}`, { token: author.token })
    const again = await api('GET', `/posts/${viewPost.id}`, { token: author.token })
    assert((again.viewCount ?? 0) === 0, `作者自己刷新不应计数，实际 ${again.viewCount}`)
  })

  await step('浏览量：同一账号 30 分钟内重复访问只计一次', async () => {
    const first = await api('GET', `/posts/${viewPost.id}`, { token: reader.token })
    assert((first.viewCount ?? 0) === 1, `首次访问应计 1 次，实际 ${first.viewCount}`)

    const second = await api('GET', `/posts/${viewPost.id}`, { token: reader.token })
    assert((second.viewCount ?? 0) === 1, `30 分钟内重复访问不应再计，实际 ${second.viewCount}`)

    const third = await api('GET', `/posts/${viewPost.id}`, { token: reader.token })
    assert((third.viewCount ?? 0) === 1, `第三次访问仍不应计数，实际 ${third.viewCount}`)
  })

  await step('浏览量：换账号（换会话）再计一次', async () => {
    const other = await api('GET', `/posts/${viewPost.id}`, { token: otherReader.token })
    assert((other.viewCount ?? 0) === 2, `换账号访问应计到 2，实际 ${other.viewCount}`)
  })

  // ------------------------------------------------------------------
  // 二、敏感词三模式
  // ------------------------------------------------------------------
  const postWithWord = (token, extra = '') => api('POST', `/channels/${channel.id}/posts`, {
    token,
    expect: [201],
    body: {
      boardId: board.id,
      title: `敏感词探针 ${suffix()}`,
      content: `正文里包含 ${word} 这个词${extra}`,
      contentFormat: 'PLAIN',
    },
  })

  await step('敏感词：创建词条并记录当前全局模式', async () => {
    const created = await api('POST', '/admin/sensitive-words', {
      token: admin.token,
      expect: [201],
      body: { word, category: 'CUSTOM', action: 'REVIEW', enabled: true },
    })
    cleanup.wordId = created.id

    const groups = await api('GET', '/admin/config', { token: admin.token })
    const item = groups.groups.flatMap((group) => group.items).find((entry) => entry.key === MODE_KEY)
    assert(item, '配置里应存在敏感词模式项')
    cleanup.originalMode = item.value
    cleanup.originalModeOverridden = item.overridden === true
  })

  await step('敏感词 REVIEW：内容照常入库，只记录命中', async () => {
    await setMode('REVIEW')
    const post = await postWithWord(author.token)
    assert(post.content.includes(word), `REVIEW 模式不应改动正文，实际 ${post.content}`)

    const hits = await api('GET', `/admin/sensitive-words/hits?word=${encodeURIComponent(word)}&size=20`, {
      token: admin.token,
    })
    const hit = (hits.items ?? []).find((item) => item.targetId === post.id)
    assert(hit, 'REVIEW 模式应记录命中')
    assert(hit.action === 'REVIEW', `命中记录 action 应为 REVIEW，实际 ${hit.action}`)
  })

  await step('敏感词 MASK：入库内容命中词变成 ***', async () => {
    await setMode('MASK')
    const post = await postWithWord(author.token)
    assert(!post.content.includes(word), `MASK 模式入库内容不应含原词，实际 ${post.content}`)
    assert(post.content.includes('***'), `MASK 模式应出现 ***，实际 ${post.content}`)

    const hits = await api('GET', `/admin/sensitive-words/hits?word=${encodeURIComponent(word)}&size=20`, {
      token: admin.token,
    })
    const hit = (hits.items ?? []).find((item) => item.targetId === post.id)
    assert(hit && hit.action === 'MASK', `命中记录 action 应为 MASK，实际 ${hit?.action}`)
  })

  await step('敏感词 BLOCK：发布返回 422 SENSITIVE_WORD_BLOCKED', async () => {
    await setMode('BLOCK')
    const error = await api('POST', `/channels/${channel.id}/posts`, {
      token: author.token,
      expect: [422],
      body: {
        boardId: board.id,
        title: `敏感词拦截探针 ${suffix()}`,
        content: `这条会被拦：${word}`,
        contentFormat: 'PLAIN',
      },
    })
    assert(
      error?.error?.code === 'SENSITIVE_WORD_BLOCKED',
      `错误码应为 SENSITIVE_WORD_BLOCKED，实际 ${error?.error?.code}`,
    )
  })

  await step('敏感词：恢复原模式后同一内容可正常发布', async () => {
    await restoreMode()
    cleanup.modeTouched = false
    const post = await postWithWord(author.token, '（恢复后）')
    assert(Boolean(post.id), '恢复模式后应能正常发布')
  })

  // ------------------------------------------------------------------
  // 三、搜索缩略图
  // ------------------------------------------------------------------
  await step('搜索缩略图：显式封面帖返回 coverUrl，纯媒体帖返回首图', async () => {
    const coverPost = await api('POST', `/channels/${channel.id}/posts`, {
      token: author.token,
      expect: [201],
      body: {
        boardId: board.id,
        title: `${searchKeyword} 有封面的帖子`,
        content: `${searchKeyword} 封面帖正文`,
        contentFormat: 'PLAIN',
        coverUrl: '/uploads/2026/11/search-cover.png',
      },
    })
    const mediaPost = await api('POST', `/channels/${channel.id}/posts`, {
      token: author.token,
      expect: [201],
      body: {
        boardId: board.id,
        title: `${searchKeyword} 纯媒体帖`,
        content: `${searchKeyword} 媒体帖正文`,
        contentFormat: 'PLAIN',
        media: [
          { type: 'IMAGE', url: '/uploads/2026/11/search-media-1.png', thumbUrl: '/uploads/2026/11/search-media-1-thumb.png' },
        ],
      },
    })

    const found = await api('GET', `/search?q=${encodeURIComponent(searchKeyword)}&type=post&limit=20`)
    const items = found.items ?? []
    const coverItem = items.find((item) => item.id === coverPost.id)
    const mediaItem = items.find((item) => item.id === mediaPost.id)

    assert(coverItem, '搜索结果应包含有封面的帖子')
    assert(mediaItem, '搜索结果应包含纯媒体帖')
    assert(coverItem.coverUrl === '/uploads/2026/11/search-cover.png',
      `封面帖应带 coverUrl，实际 ${coverItem.coverUrl}`)
    assert(Array.isArray(mediaItem.media) && mediaItem.media.length > 0,
      `纯媒体帖应带首图，实际 ${JSON.stringify(mediaItem.media)}`)
    assert(mediaItem.media[0].url === '/uploads/2026/11/search-media-1.png',
      `首图应为 sort 最小的那张，实际 ${mediaItem.media[0].url}`)
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
