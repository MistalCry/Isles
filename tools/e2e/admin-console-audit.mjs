#!/usr/bin/env node
/**
 * 平台后台自动化护栏（docs/11 §10、docs/08 §5 场景 8）。
 *
 * 覆盖后台四段主线，全部通过公开 HTTP 契约验证，可重复执行：
 *   1. 配置中心闭环：改站点名 → 公开配置生效 → 重置 → 无覆盖行残留；
 *   2. 举报治理闭环：举报 → 处置 ACCEPTED → 内容下架 → 作者收通知 → 恢复 → 前台可见；
 *   3. 权限边界：授权 MODERATOR → 能处置举报、改配置 403、不能授权管理员；
 *   4. 公告状态机：草稿不公开 → 发布公开 → 已发布删除 422 → 归档后可删。
 * 另含「置顶回归」（依赖后台 P0-6，接口未就绪时跳过并标注）。
 *
 * 运行：
 *   node tools/e2e/admin-console-audit.mjs
 *   BASE_URL=http://127.0.0.1:8082/api/v1 node tools/e2e/admin-console-audit.mjs
 *
 * 需要一个平台超管账号（默认 admin_demo / Passw0rd123，可用环境变量覆盖）：
 *   ADMIN_USERNAME=xxx ADMIN_PASSWORD=yyy node tools/e2e/admin-console-audit.mjs
 * 没有超管时先用 `isles.bat admin <用户名>` 把一个账号提为平台管理员。
 *
 * 数据清理：脚本自己造的公告/授权/置顶/频道在结束时清理；站点名改完立即重置，
 * 失败时也会在收尾阶段兜底重置，避免 platform_settings 残留覆盖行。
 * 注册限流 5 次/小时/IP，重复运行请用 RATE_LIMIT_ENABLED=false 启动后端。
 */

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1'
const ADMIN_USERNAME = process.env.ADMIN_USERNAME || 'admin_demo'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Passw0rd123'

const results = []
/** 失败也要兜底清理的资源 */
const cleanup = {
  settingTouched: false,
  settingKey: 'site.name',
  /** 运行前的取值：若原本就是「覆盖值」，收尾要还原，不能顺手把别人的配置清掉 */
  settingBaseline: null,
  settingBaselineOverridden: false,
  announcementId: null,
  moderatorUserId: null,
  channelId: null,
  pinnedPostId: null,
}

class HttpError extends Error {
  constructor(status, body, label) {
    super(`${status} ${label}: ${typeof body === 'string' ? body : JSON.stringify(body)}`)
    this.status = status
    this.body = body
  }
}

async function apiRaw(method, path, { token, body, expect = [200], headers: extra = {} } = {}) {
  const headers = { Accept: 'application/json', ...extra }
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
    // 非 JSON 响应保留原文，便于报错时定位
  }
  if (!expect.includes(response.status)) {
    throw new HttpError(response.status, data, `${method} ${path}`)
  }
  return { status: response.status, data, headers: response.headers, text }
}

async function api(method, path, options = {}) {
  const { data } = await apiRaw(method, path, options)
  return data
}

function assert(condition, message) {
  if (!condition) throw new Error(`断言失败：${message}`)
}

/** 用例内部判定「依赖未就绪」时抛出，交给 step 记为跳过而不是失败 */
class SkipCase extends Error {}

/** 前置准备失败后，后续用例不再逐个报「undefined」噪音，统一记为跳过 */
let blockedReason = null

async function step(name, fn) {
  if (blockedReason) {
    skip(name, blockedReason)
    return
  }
  const started = Date.now()
  try {
    await fn()
    results.push({ name, status: 'pass', ms: Date.now() - started })
    console.log(`  ✓ ${name}`)
  } catch (error) {
    if (error instanceof SkipCase) {
      skip(name, error.message)
      return
    }
    results.push({ name, status: 'fail', error: error.message, ms: Date.now() - started })
    console.error(`  ✗ ${name}\n    ${error.message}`)
  }
}

function skip(name, reason) {
  results.push({ name, status: 'skip', note: reason })
  console.log(`  ⊘ ${name}\n    跳过：${reason}`)
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

async function createChannel(owner) {
  const channel = await api('POST', '/channels', {
    token: owner.token,
    expect: [201],
    body: {
      name: `Admin Audit ${suffix()}`,
      avatarUrl: '/uploads/default-avatar.png',
      type: 'PUBLIC',
    },
  })
  const boards = await api('GET', `/channels/${channel.id}/boards`)
  return { channel, board: boards.items[0] }
}

async function createPost(author, channelId, boardId, content) {
  return api('POST', `/channels/${channelId}/posts`, {
    token: author.token,
    expect: [201],
    body: { boardId, title: content, content, contentFormat: 'PLAIN' },
  })
}

function findSetting(groups, key) {
  for (const group of groups) {
    const hit = group.items.find((item) => item.key === key)
    if (hit) return hit
  }
  return null
}

async function adminSettings(token) {
  const data = await api('GET', '/admin/config', { token })
  return data.groups
}

async function publicConfig() {
  return api('GET', '/platform/config')
}

async function listNotifications(token) {
  const data = await api('GET', '/notifications?limit=50', { token })
  return data.items ?? []
}

/**
 * 用举报描述里的唯一标记定位举报，避免依赖「第一页」或翻页（dev 库举报越积越多时会误判）。
 * 后台 keyword 匹配 description / reason（AdminReportMapper#selectPage）。
 */
async function findAdminReport(token, keyword) {
  const page = await api(
    'GET',
    `/admin/reports?status=PENDING&targetType=POST&size=20&keyword=${encodeURIComponent(keyword)}`,
    { token },
  )
  assert(page.available !== false, '举报表未就绪（available=false），后台举报链路不可用')
  return (page.items ?? [])[0] ?? null
}

async function runCleanup() {
  console.log('\n清理：')
  const admin = cleanup.adminToken
  try {
    if (cleanup.settingTouched && admin) {
      await api('POST', `/admin/config/${cleanup.settingKey}/reset`, { token: admin })
      console.log('  ✓ 站点名已重置为默认值')
      cleanup.settingTouched = false
    }
  } catch (error) {
    console.error(`  ! 站点名重置失败：${error.message}`)
  }
  try {
    if (cleanup.settingBaselineOverridden && admin && cleanup.settingBaseline !== null) {
      await api('PUT', `/admin/config/${cleanup.settingKey}`, {
        token: admin,
        body: { value: cleanup.settingBaseline },
      })
      console.log(`  ✓ 已还原运行前的覆盖值：${cleanup.settingKey}=${cleanup.settingBaseline}`)
    }
  } catch (error) {
    console.error(`  ! 还原覆盖值失败：${error.message}`)
  }
  try {
    if (cleanup.announcementId && admin) {
      // 已发布不能直接删，先归档
      await api('POST', `/admin/announcements/${cleanup.announcementId}/archive`, { token: admin, expect: [200, 404, 422] })
      await api('DELETE', `/admin/announcements/${cleanup.announcementId}`, { token: admin, expect: [204, 404, 422] })
      console.log('  ✓ 公告已删除')
      cleanup.announcementId = null
    }
  } catch (error) {
    console.error(`  ! 公告清理失败：${error.message}`)
  }
  try {
    if (cleanup.moderatorUserId && admin) {
      await api('DELETE', `/admin/admins/${cleanup.moderatorUserId}`, { token: admin, expect: [204, 404] })
      console.log('  ✓ MODERATOR 授权已撤销')
      cleanup.moderatorUserId = null
    }
  } catch (error) {
    console.error(`  ! 撤销授权失败：${error.message}`)
  }
  try {
    if (cleanup.pinnedPostId && cleanup.channelId && cleanup.ownerToken) {
      await api('DELETE', `/channels/${cleanup.channelId}/posts/${cleanup.pinnedPostId}/pin`, {
        token: cleanup.ownerToken,
        expect: [200, 404],
      })
      console.log('  ✓ 置顶已取消')
      cleanup.pinnedPostId = null
    }
  } catch (error) {
    console.error(`  ! 取消置顶失败：${error.message}`)
  }
  try {
    if (cleanup.channelId && cleanup.ownerToken) {
      await api('POST', `/channels/${cleanup.channelId}/dissolve`, {
        token: cleanup.ownerToken,
        expect: [204, 404],
      })
      console.log('  ✓ 测试频道已解散')
      cleanup.channelId = null
    }
  } catch (error) {
    console.error(`  ! 解散频道失败：${error.message}`)
  }
}

async function main() {
  console.log(`\n群岛平台后台护栏审计 · ${BASE_URL}\n`)

  const health = await fetch(`${BASE_URL.replace(/\/api\/v1$/, '')}/actuator/health`).catch(() => null)
  if (!health || !health.ok) {
    console.error('后端不可用，请先启动后端')
    process.exit(1)
  }

  let admin
  let author
  let reporter
  let moderator
  let channel
  let board
  let reportPost
  let reportId
  let reportMarker
  let modReportPost
  let announcementTitle

  // ------------------------------------------------------------------
  // 准备
  // ------------------------------------------------------------------
  await step('准备：登录平台超管，确认 CONFIG_WRITE 权限', async () => {
    const login = await api('POST', '/auth/login', {
      body: { username: ADMIN_USERNAME, password: ADMIN_PASSWORD },
    })
    admin = { username: ADMIN_USERNAME, id: login.user.id, token: login.accessToken }
    cleanup.adminToken = admin.token
    const me = await api('GET', '/admin/me', { token: admin.token })
    assert(me.admin === true, `账号 ${ADMIN_USERNAME} 不是平台管理员（用 isles.bat admin 提升）`)
    assert(me.permissions.includes('CONFIG_WRITE'), '超管应具备 CONFIG_WRITE')
    assert(me.permissions.includes('ADMIN_MANAGE'), '超管应具备 ADMIN_MANAGE')
  })

  if (!admin) {
    console.error('未取得超管令牌，后续用例无法执行\n')
    process.exit(1)
  }

  await step('准备：三个带时间戳账号 + 公开频道 + 板块', async () => {
    author = await newUser('admauth')
    reporter = await newUser('admrep')
    moderator = await newUser('admmod')
    const created = await createChannel(author)
    channel = created.channel
    board = created.board
    cleanup.channelId = channel.id
    cleanup.ownerToken = author.token
    await api('POST', `/channels/${channel.id}/join`, { token: reporter.token })
  })

  if (!author || !reporter || !moderator || !channel || !board) {
    blockedReason = '前置准备失败（账号或频道未创建成功），本用例跳过；常见原因：注册限流 429，请用 RATE_LIMIT_ENABLED=false 重启后端'
  }

  // ------------------------------------------------------------------
  // 一、配置中心闭环
  // ------------------------------------------------------------------
  // 基线以「默认值 + 是否已被覆盖」为准：reset 之后回到的是默认值，
  // 而不是运行前的取值（运行前可能本来就有别人设的覆盖值）。
  let baselineName = null
  let defaultValue = null
  await step('配置：读取 site.name 基线与默认值', async () => {
    const groups = await adminSettings(admin.token)
    const item = findSetting(groups, 'site.name')
    assert(item, '配置列表应包含 site.name')
    baselineName = item.value
    defaultValue = item.defaultValue
    cleanup.settingBaseline = item.overridden ? item.value : null
    cleanup.settingBaselineOverridden = item.overridden === true
  })

  await step('配置：改站点名后公开配置立即生效（不重启）', async () => {
    const probeName = `群岛E2E${suffix()}`.slice(0, 40)
    assert(probeName !== baselineName, '探针名不应与当前站点名相同')
    const updated = await api('PUT', '/admin/config/site.name', {
      token: admin.token,
      body: { value: probeName },
    })
    cleanup.settingTouched = true
    assert(updated.value === probeName, '写接口应回显新值')
    assert(updated.overridden === true, '写入后 overridden 应为 true')

    const publicAfter = await publicConfig()
    assert(publicAfter.values['site.name'] === probeName,
      `公开配置应即时生效，实际 ${publicAfter.values['site.name']}`)
  })

  await step('配置：60 秒内连续改两次都即时生效，响应禁止缓存（防 60s 缓存回归）', async () => {
    // 用户实测 bug：改完站点名，60 秒内读到的还是旧值。
    // 这里在 60 秒窗口内连续改两次 + 恢复默认，任何进程内 TTL 或 HTTP 缓存都会当场暴露。
    const firstName = `群岛E2E1${suffix()}`.slice(0, 40)
    const secondName = `群岛E2E2${suffix()}`.slice(0, 40)
    const startedAt = Date.now()

    await api('PUT', '/admin/config/site.name', { token: admin.token, body: { value: firstName } })
    cleanup.settingTouched = true
    const firstRead = await apiRaw('GET', '/platform/config')
    assert(
      firstRead.data.values['site.name'] === firstName,
      `第一次修改后应立刻读到新值，实际 ${firstRead.data.values['site.name']}`,
    )
    const cacheControl = (firstRead.headers.get('cache-control') ?? '').toLowerCase()
    assert(cacheControl.includes('no-store'), `公开配置必须 no-store，实际 Cache-Control=${cacheControl || '(空)'}`)
    assert(!firstRead.headers.get('etag'), '公开配置不应带 ETag（304 会让浏览器沿用旧值）')

    // 第二次修改仍在 60 秒内：有 TTL 的实现会在这里返回上一次的值
    await api('PUT', '/admin/config/site.name', { token: admin.token, body: { value: secondName } })
    const secondRead = await apiRaw('GET', '/platform/config')
    assert(
      secondRead.data.values['site.name'] === secondName,
      `60 秒内第二次修改也应即时生效，实际 ${secondRead.data.values['site.name']}`,
    )

    // 恢复默认同样即时
    await api('POST', '/admin/config/site.name/reset', { token: admin.token })
    cleanup.settingTouched = false
    const resetRead = await apiRaw('GET', '/platform/config')
    assert(
      resetRead.data.values['site.name'] === defaultValue,
      `恢复默认应即时生效，实际 ${resetRead.data.values['site.name']}`,
    )

    const elapsed = Date.now() - startedAt
    assert(elapsed < 5000, `两次改写 + 三次读回应远快于 60 秒窗口，实际 ${elapsed}ms`)
  })

  await step('配置：重置后回到默认值，platform_settings 无残留行', async () => {
    const reset = await api('POST', '/admin/config/site.name/reset', { token: admin.token })
    cleanup.settingTouched = false
    assert(reset.overridden === false, '重置后 overridden 应为 false（即无覆盖行）')
    assert(reset.value === reset.defaultValue, '重置后取值应等于默认值')

    const publicAfter = await publicConfig()
    assert(publicAfter.values['site.name'] === defaultValue,
      `公开配置应回到默认值 ${defaultValue}，实际 ${publicAfter.values['site.name']}`)
    // 后续 403 用例用「重置后的实际取值」做基线，避免被运行前的覆盖值干扰
    baselineName = defaultValue

    // overridden 由 platform_settings 覆盖行计算（PlatformSettingService#list），
    // 因此 overridden=false 等价于该 key 在 platform_settings 中没有残留行。
    const groups = await adminSettings(admin.token)
    const item = findSetting(groups, 'site.name')
    assert(item, '配置列表应包含 site.name')
    assert(item.overridden === false, 'site.name 不应残留覆盖行')
    assert(item.value === item.defaultValue, 'site.name 取值应等于默认值')
  })

  // ------------------------------------------------------------------
  // 二、举报治理闭环
  // ------------------------------------------------------------------
  await step('举报：作者发帖 → 举报人提交举报 → 后台可见 PENDING', async () => {
    reportPost = await createPost(author, channel.id, board.id, `举报目标帖 ${suffix()}`)
    reportMarker = `E2E-${suffix()}`
    const report = await api('POST', '/reports', {
      token: reporter.token,
      expect: [201],
      body: {
        targetType: 'POST',
        targetId: reportPost.id,
        reason: 'SPAM',
        description: `E2E 自动化举报 ${reportMarker}`,
      },
    })
    assert(report.status === 'PENDING', `新举报状态应为 PENDING，实际 ${report.status}`)
    reportId = report.id
    const found = await findAdminReport(admin.token, reportMarker)
    assert(found && found.id === reportId, '后台举报列表应能按 PENDING + 关键字查到该举报')
  })

  await step('举报：处置 RESOLVE + DELETE_CONTENT → 状态 ACCEPTED 且内容下架', async () => {
    const handled = await api('POST', `/admin/reports/${reportId}/handle`, {
      token: admin.token,
      body: { action: 'RESOLVE', handleAction: 'DELETE_CONTENT', note: 'E2E 自动处置' },
    })
    assert(handled.status === 'ACCEPTED', `处置后状态应为 ACCEPTED，实际 ${handled.status}`)
    assert(handled.action === 'RESOLVE', '回显动作应为 RESOLVE')

    const groups = await adminSettings(admin.token)
    assert(findSetting(groups, 'site.name').overridden === false, '处置不应污染配置')

    await api('GET', `/posts/${reportPost.id}`, { expect: [404] })
    await api('GET', `/posts/${reportPost.id}`, { token: author.token, expect: [404] })
    const feed = await api('GET', `/channels/${channel.id}/posts?limit=20`)
    assert(!(feed.items ?? []).some((item) => item.id === reportPost.id), '下架后频道流不应出现该帖')
  })

  await step('举报：作者收到「帖子已被平台下架」通知', async () => {
    const notifications = await listNotifications(author.token)
    const hit = notifications.find((item) => item.type === 'POST_REMOVED' && item.postId === reportPost.id)
    assert(hit, '作者应收到 POST_REMOVED 通知')
    assert((hit.title ?? '').includes('下架'), `通知标题应说明下架，实际「${hit.title}」`)
    assert((hit.content ?? '').includes('举报核实'), '通知正文应带处置原因')
  })

  await step('举报：恢复后帖子详情与频道流重新可见', async () => {
    const restored = await api('POST', `/admin/posts/${reportPost.id}/restore`, { token: admin.token })
    assert(restored.status === 'PUBLISHED', `恢复后状态应为 PUBLISHED，实际 ${restored.status}`)
    const detail = await api('GET', `/posts/${reportPost.id}`)
    assert(detail.id === reportPost.id, '游客应能再次看到该帖')
    const feed = await api('GET', `/channels/${channel.id}/posts?limit=20`)
    assert((feed.items ?? []).some((item) => item.id === reportPost.id), '恢复后频道流应重新出现该帖')

    const notifications = await listNotifications(author.token)
    assert(notifications.some((item) => item.type === 'POST_REMOVED' && (item.title ?? '').includes('恢复')),
      '恢复后作者应收到「帖子已恢复」通知')
  })

  // ------------------------------------------------------------------
  // 三、MODERATOR 授权与权限边界
  // ------------------------------------------------------------------
  await step('权限：授权 MODERATOR 后 /admin/me 权限点正确', async () => {
    await api('POST', '/admin/admins', {
      token: admin.token,
      expect: [201],
      body: { userId: moderator.id, role: 'MODERATOR' },
    })
    cleanup.moderatorUserId = moderator.id
    const me = await api('GET', '/admin/me', { token: moderator.token })
    assert(me.platformRole === 'MODERATOR', `角色应为 MODERATOR，实际 ${me.platformRole}`)
    assert(me.permissions.includes('REPORT_HANDLE'), 'MODERATOR 应具备 REPORT_HANDLE')
    assert(me.permissions.includes('CONTENT_TAKEDOWN'), 'MODERATOR 应具备 CONTENT_TAKEDOWN')
    assert(!me.permissions.includes('CONFIG_WRITE'), 'MODERATOR 不应具备 CONFIG_WRITE')
    assert(!me.permissions.includes('ADMIN_MANAGE'), 'MODERATOR 不应具备 ADMIN_MANAGE')
  })

  await step('权限：MODERATOR 能处置举报', async () => {
    modReportPost = await createPost(author, channel.id, board.id, `审核员处置目标 ${suffix()}`)
    const report = await api('POST', '/reports', {
      token: reporter.token,
      expect: [201],
      body: { targetType: 'POST', targetId: modReportPost.id, reason: 'ABUSE', description: 'E2E 审核员处置' },
    })
    const handled = await api('POST', `/admin/reports/${report.id}/handle`, {
      token: moderator.token,
      body: { action: 'RESOLVE', handleAction: 'NONE', note: 'MODERATOR 处置' },
    })
    assert(handled.status === 'ACCEPTED', `MODERATOR 处置后应为 ACCEPTED，实际 ${handled.status}`)
    // NONE 不处置内容，帖子仍可见
    await api('GET', `/posts/${modReportPost.id}`)
  })

  await step('权限：MODERATOR 改配置返回 403（读配置仍允许）', async () => {
    const groups = await adminSettings(moderator.token)
    assert(findSetting(groups, 'site.name'), 'MODERATOR 应可读配置（CONFIG_READ）')
    await api('PUT', '/admin/config/site.name', {
      token: moderator.token,
      expect: [403],
      body: { value: '不该生效的名字' },
    })
    const publicAfter = await publicConfig()
    assert(publicAfter.values['site.name'] === baselineName, '403 之后站点名不应变化')
  })

  await step('权限：MODERATOR 不能授权管理员、不能看管理员列表', async () => {
    await api('POST', '/admin/admins', {
      token: moderator.token,
      expect: [403],
      body: { userId: reporter.id, role: 'VIEWER' },
    })
    await api('GET', '/admin/admins', { token: moderator.token, expect: [403] })
  })

  await step('权限：撤销 MODERATOR 后立即失去后台访问（缓存失效）', async () => {
    await api('DELETE', `/admin/admins/${moderator.id}`, { token: admin.token, expect: [204] })
    cleanup.moderatorUserId = null
    // 后台接口 403
    await api('GET', '/admin/reports', { token: moderator.token, expect: [403] })
    // /admin/me 对任意登录用户可用（前端探测后台入口），撤销后应回落为 admin=false
    const me = await api('GET', '/admin/me', { token: moderator.token })
    assert(me.admin === false, `撤销后 /admin/me 应返回 admin=false，实际 ${me.admin}`)
    assert((me.permissions ?? []).length === 0, '撤销后权限点应为空')
    assert(me.platformRole === 'USER', `撤销后角色应回落为 USER，实际 ${me.platformRole}`)
  })

  // ------------------------------------------------------------------
  // 四、公告状态机
  // ------------------------------------------------------------------
  await step('公告：草稿不出现在公开接口', async () => {
    const title = `E2E 公告 ${suffix()}`
    const draft = await api('POST', '/admin/announcements', {
      token: admin.token,
      expect: [201],
      body: { title, content: '自动化审计创建的公告，运行结束会删除。', level: 'INFO', placement: 'BANNER' },
    })
    cleanup.announcementId = draft.id
    assert(draft.status === 'DRAFT', `新建公告应为 DRAFT，实际 ${draft.status}`)
    const publicList = await api('GET', '/platform/announcements')
    assert(!(publicList.items ?? []).some((item) => item.id === draft.id), '草稿不应出现在公开公告里')
    announcementTitle = title
  })

  await step('公告：发布后公开可见', async () => {
    const published = await api('POST', `/admin/announcements/${cleanup.announcementId}/publish`, {
      token: admin.token,
    })
    assert(published.status === 'PUBLISHED', `发布后应为 PUBLISHED，实际 ${published.status}`)
    const publicList = await api('GET', '/platform/announcements')
    const hit = (publicList.items ?? []).find((item) => item.id === cleanup.announcementId)
    assert(hit, '发布后公开公告应包含该条')
    assert(hit.title === announcementTitle, '公开公告标题应与发布内容一致')
  })

  await step('公告：已发布直接删除返回 422（必须先归档）', async () => {
    await api('DELETE', `/admin/announcements/${cleanup.announcementId}`, {
      token: admin.token,
      expect: [422],
    })
    const publicList = await api('GET', '/platform/announcements')
    assert((publicList.items ?? []).some((item) => item.id === cleanup.announcementId),
      '删除被拒后公告应仍然存在')
  })

  await step('公告：归档后可删除，公开接口不再返回', async () => {
    const archived = await api('POST', `/admin/announcements/${cleanup.announcementId}/archive`, {
      token: admin.token,
    })
    assert(archived.status === 'ARCHIVED', `归档后应为 ARCHIVED，实际 ${archived.status}`)
    await api('DELETE', `/admin/announcements/${cleanup.announcementId}`, {
      token: admin.token,
      expect: [204],
    })
    cleanup.announcementId = null
    const publicList = await api('GET', '/platform/announcements')
    assert(!(publicList.items ?? []).some((item) => item.id === archived.id), '删除后不应再出现在公开接口')
  })

  // ------------------------------------------------------------------
  // 五、置顶回归（依赖后台 P0-6）
  // ------------------------------------------------------------------
  let pinVerified = false
  await step('置顶：置顶旧帖后频道首屏（不翻页）可见置顶区', async () => {
    const target = await createPost(author, channel.id, board.id, `置顶目标帖 ${suffix()}`)
    // 再造 3 条更新的帖子：若列表不按 is_pinned 优先排序，目标帖会被挤出首屏
    for (let i = 0; i < 3; i += 1) {
      await createPost(author, channel.id, board.id, `后续新帖 ${i} ${suffix()}`)
    }
    const pinned = await api('POST', `/channels/${channel.id}/posts/${target.id}/pin`, {
      token: author.token,
      expect: [200],
    }).catch((error) => {
      // 依赖后台 P0-6：接口未就绪时跳过而不是判失败
      if (error.status === 404 || error.status === 405) {
        throw new SkipCase('置顶接口未就绪（P0-6）：POST /channels/{id}/posts/{postId}/pin 返回 404/405，等后台交付后复跑')
      }
      throw error
    })
    cleanup.pinnedPostId = target.id
    assert(pinned.isPinned === true || pinned.pinned === true, '置顶接口应回显置顶状态')

    // 首屏（limit=3）：只有 3 个位置，置顶帖必须在其中且排第一
    const firstPage = await api('GET', `/channels/${channel.id}/posts?limit=3`)
    const ids = (firstPage.items ?? []).map((item) => item.id)
    assert(ids[0] === target.id, `置顶帖应排在首屏第一位，实际首屏 ${ids.join(',')}`)

    const pinnedArea = await api('GET', `/channels/${channel.id}/posts/pinned?limit=20`)
    assert((pinnedArea.items ?? []).some((item) => item.id === target.id), '置顶区应包含该帖')
    pinVerified = true
  })

  if (pinVerified) {
    skip(
      '置顶：30 天前旧帖场景',
      '创建接口无法回填 created_at（无测试后门），已用「置顶帖 + 3 条更新帖 + limit=3 首屏」等价覆盖排序优先级；' +
        '30 天跨度需后台提供数据回填手段后再补',
    )
  }

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
  console.error(`\n审计脚本异常：${error.message}\n`)
  exitCode = 1
} finally {
  await runCleanup()
}
process.exit(exitCode)
