#!/usr/bin/env node
/**
 * 举报链路回归（用户实测 bug：前端把中文文案当 reason 代码发 → 举报必挂）。
 *
 * 后端 `ReportCreateRequest.reason` 只认 `SPAM|ABUSE|PORN|ILLEGAL|OTHER`，
 * 界面文案（「垃圾广告」/「Spam」）必须只用于展示。本脚本从接口层锁死契约：
 *   1. 中文界面场景：举报一条帖子 + 一条评论，均成功；
 *   2. 英文界面场景：举报一条帖子 + 一条评论，均成功；
 *   3. 五种原因代码逐个举报都能成功（帖子/评论各覆盖）；
 *   4. 反例：把界面文案当 reason 发 → 400（这就是当初必挂的形态，必须继续被拒）。
 *
 * 前端「代码 vs 文案」的映射由 `frontend/src/components/__tests__/ReportDialog.spec.ts` 锁定，
 * 本脚本负责接口侧契约，两者合起来覆盖「中英文界面都能举报成功」。
 *
 * 运行：node tools/e2e/report-regression.mjs
 * 注册限流 5 次/小时/IP，重复跑请用 RATE_LIMIT_ENABLED=false 启动后端。
 */

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1'

/** 后端 @Pattern 白名单；顺序与前端 REPORT_REASON_CODES 一致 */
const REASON_CODES = ['SPAM', 'ABUSE', 'PORN', 'ILLEGAL', 'OTHER']

const results = []
const cleanup = { channelId: null, ownerToken: null }

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

async function step(name, fn) {
  if (blockedReason) {
    results.push({ name, status: 'skip', note: blockedReason })
    console.log(`  ⊘ ${name}\n    跳过：${blockedReason}`)
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

async function createPost(author, channelId, boardId, content) {
  return api('POST', `/channels/${channelId}/posts`, {
    token: author.token,
    expect: [201],
    body: { boardId, title: content, content, contentFormat: 'PLAIN' },
  })
}

async function createComment(author, postId, content) {
  return api('POST', `/posts/${postId}/comments`, {
    token: author.token,
    expect: [201],
    body: { content },
  })
}

async function runCleanup() {
  if (!cleanup.channelId || !cleanup.ownerToken) return
  try {
    await api('POST', `/channels/${cleanup.channelId}/dissolve`, {
      token: cleanup.ownerToken,
      expect: [204, 404],
    })
    console.log('\n清理：\n  ✓ 测试频道已解散')
  } catch (error) {
    console.error(`\n清理：\n  ! 解散频道失败：${error.message}`)
  }
}

async function main() {
  console.log(`\n群岛举报链路回归 · ${BASE_URL}\n`)

  const health = await fetch(`${BASE_URL.replace(/\/api\/v1$/, '')}/actuator/health`).catch(() => null)
  if (!health || !health.ok) {
    console.error('后端不可用，请先启动后端')
    process.exit(1)
  }

  let author
  let reporter
  let channel
  let board
  const posts = {}
  const comments = {}
  const reported = []

  await step('准备：作者 + 举报人 + 公开频道 + 三条帖子两条评论', async () => {
    author = await newUser('rptauth')
    reporter = await newUser('rptrep')
    channel = await api('POST', '/channels', {
      token: author.token,
      expect: [201],
      body: { name: `Report Audit ${suffix()}`, avatarUrl: '/uploads/default-avatar.png', type: 'PUBLIC' },
    })
    cleanup.channelId = channel.id
    cleanup.ownerToken = author.token
    const boards = await api('GET', `/channels/${channel.id}/boards`)
    board = boards.items[0]
    await api('POST', `/channels/${channel.id}/join`, { token: reporter.token })

    posts.zh = await createPost(author, channel.id, board.id, `中文场景举报目标 ${suffix()}`)
    posts.en = await createPost(author, channel.id, board.id, `English scenario target ${suffix()}`)
    posts.extra = await createPost(author, channel.id, board.id, `原因矩阵目标 ${suffix()}`)
    comments.zh = await createComment(author, posts.zh.id, `中文场景评论 ${suffix()}`)
    comments.en = await createComment(author, posts.en.id, `English scenario comment ${suffix()}`)
  })

  if (!author || !reporter || !posts.extra) {
    blockedReason = '前置准备失败（账号/频道/帖子未创建成功），常见原因：注册限流 429，请用 RATE_LIMIT_ENABLED=false 启动后端'
  }

  await step('中文界面场景：举报一条帖子（SPAM）与一条评论（ABUSE）都成功', async () => {
    const postReport = await api('POST', '/reports', {
      token: reporter.token,
      expect: [201],
      body: {
        targetType: 'POST',
        targetId: posts.zh.id,
        reason: 'SPAM',
        description: '中文界面：垃圾广告，重复刷屏',
      },
    })
    assert(postReport.status === 'PENDING', `帖子举报状态应为 PENDING，实际 ${postReport.status}`)
    assert(postReport.reason === 'SPAM', `落库 reason 应为代码 SPAM，实际 ${postReport.reason}`)

    const commentReport = await api('POST', '/reports', {
      token: reporter.token,
      expect: [201],
      body: {
        targetType: 'COMMENT',
        targetId: comments.zh.id,
        reason: 'ABUSE',
        description: '中文界面：人身攻击',
      },
    })
    assert(commentReport.reason === 'ABUSE', `落库 reason 应为代码 ABUSE，实际 ${commentReport.reason}`)
    reported.push(postReport.id, commentReport.id)
  })

  await step('英文界面场景：举报一条帖子（PORN）与一条评论（ILLEGAL）都成功', async () => {
    const postReport = await api('POST', '/reports', {
      token: reporter.token,
      expect: [201],
      body: {
        targetType: 'POST',
        targetId: posts.en.id,
        reason: 'PORN',
        description: 'English UI: explicit content',
      },
    })
    assert(postReport.reason === 'PORN', `落库 reason 应为代码 PORN，实际 ${postReport.reason}`)

    const commentReport = await api('POST', '/reports', {
      token: reporter.token,
      expect: [201],
      body: {
        targetType: 'COMMENT',
        targetId: comments.en.id,
        reason: 'ILLEGAL',
        description: 'English UI: illegal content',
      },
    })
    assert(commentReport.reason === 'ILLEGAL', `落库 reason 应为代码 ILLEGAL，实际 ${commentReport.reason}`)
    reported.push(postReport.id, commentReport.id)
  })

  await step('原因矩阵：五种代码都能提交（OTHER 补最后一种）', async () => {
    const report = await api('POST', '/reports', {
      token: reporter.token,
      expect: [201],
      body: { targetType: 'POST', targetId: posts.extra.id, reason: 'OTHER', description: '其它原因' },
    })
    assert(report.reason === 'OTHER', `落库 reason 应为代码 OTHER，实际 ${report.reason}`)
    reported.push(report.id)

    const mine = await api('GET', '/reports/mine?limit=20', { token: reporter.token })
    const stored = (mine.items ?? []).filter((item) => reported.includes(item.id)).map((item) => item.reason)
    const covered = new Set(stored)
    assert(
      REASON_CODES.every((code) => covered.has(code)),
      `五次举报应覆盖全部原因代码，实际 ${[...covered].join(',')}`,
    )
    assert(
      stored.every((code) => REASON_CODES.includes(code)),
      `落库 reason 必须全是代码，实际 ${stored.join(',')}`,
    )
  })

  await step('反例：把界面文案当 reason 发 → 400（当初必挂的形态必须继续被拒）', async () => {
    for (const label of ['垃圾广告', 'Spam', 'spam']) {
      await api('POST', '/reports', {
        token: reporter.token,
        expect: [400],
        body: { targetType: 'POST', targetId: posts.extra.id, reason: label },
      })
    }
  })

  await step('反例：targetType 非法 / targetId 不存在被拒', async () => {
    await api('POST', '/reports', {
      token: reporter.token,
      expect: [400],
      body: { targetType: 'CHANNEL', targetId: posts.extra.id, reason: 'SPAM' },
    })
    // 目标不存在返回 422 REPORT_TARGET_INVALID（不是 404：举报接口不泄露目标是否存在）
    await api('POST', '/reports', {
      token: reporter.token,
      expect: [422],
      body: { targetType: 'POST', targetId: '01NOTEXIST00000000000000', reason: 'SPAM' },
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
