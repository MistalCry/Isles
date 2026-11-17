#!/usr/bin/env node
/**
 * 敏感词 fail-closed 回归（第 21 轮 P0）。
 *
 * 契约：词库查询/匹配过程抛异常时——
 *   - `content.sensitiveWord.mode=BLOCK` → **拒绝发布**，503 `SENSITIVE_WORD_UNAVAILABLE`（fail-closed）；
 *   - `REVIEW` / `MASK` → 降级为原文放行（fail-open），但记 ERROR + 指标。
 *
 * 本脚本只覆盖 BLOCK 这一半（fail-open 那半在 `SensitiveWordServiceTest` 单测里）。
 *
 * 怎么构造「词库查询异常」：词库在 MySQL 里，没有 HTTP 层可 mock，所以用
 * **临时重命名 `sensitive_words` 表** 让 `selectEnabled()` 直接抛 "table doesn't exist"。
 * 为了不等 60 秒缓存过期，先调一次「创建词条」接口——它内部 `invalidate()` 把缓存置空，
 * 下一次校验必然回源查库。窗口只有 1~2 秒，且 `try/finally` + 启动自愈会保证表被改回来。
 *
 * 运行：
 *   BASE_URL=http://127.0.0.1:8082/api/v1 node tools/e2e/wordfilter-failclosed.mjs
 * 需要平台超管（ADMIN_USERNAME / ADMIN_PASSWORD）与本机 mysql 客户端（E2E_DB_* 可覆盖）。
 */

import { execFileSync } from 'node:child_process'

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1'
const ADMIN_USERNAME = process.env.ADMIN_USERNAME || 'admin_demo'
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Passw0rd123'

const MODE_KEY = 'content.sensitiveWord.mode'
const ENABLED_KEY = 'content.sensitiveWord.enabled'
const TABLE = 'sensitive_words'
const BACKUP = 'sensitive_words_e2e_bak'

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
  channelId: null,
  ownerToken: null,
  wordId: null,
  configs: new Map(),
  /** 表被改名的标记：finally 里据此恢复 */
  tableRenamed: false,
}

class SkipCase extends Error {}

class HttpError extends Error {
  constructor(status, body, label) {
    super(`${status} ${label}: ${typeof body === 'string' ? body : JSON.stringify(body)}`)
    this.status = status
    this.body = body
  }
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
    // 非 JSON 保留原文
  }
  if (!expect.includes(response.status)) {
    throw new HttpError(response.status, data, `${method} ${path}`)
  }
  return raw ? { status: response.status, data } : data
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

// ---------------------------------------------------------------------------
// 数据库（只做「临时改名 / 改名回来」与计数校验）
// ---------------------------------------------------------------------------

function dbExec(sql) {
  try {
    return execFileSync(
      'mysql',
      ['-h', DB.host, '-P', DB.port, '-u', DB.user, `-p${DB.password}`,
        '--default-character-set=utf8mb4', DB.name, '-N', '-B', '-e', sql],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
    )
  } catch (error) {
    throw new SkipCase(
      `无法连接数据库（缺 mysql 客户端或凭据不对）：${String(error.message).split('\n')[0]}`,
    )
  }
}

function tableExists(name) {
  const out = dbExec(
    `SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema='${DB.name}' AND table_name='${name}'`,
  )
  return Number(out.trim()) > 0
}

/** 上一次异常退出可能把表留在 bak：开跑先自愈，避免污染其他人 */
function healTableIfNeeded() {
  if (tableExists(BACKUP)) {
    console.log(`  ! 检测到残留的 ${BACKUP}，先恢复（上次运行异常退出）`)
    if (tableExists(TABLE)) {
      // 两张都在：说明是别的东西，别乱动
      throw new Error(`同时存在 ${TABLE} 与 ${BACKUP}，请人工确认后再跑`)
    }
    dbExec(`RENAME TABLE \`${BACKUP}\` TO \`${TABLE}\``)
    console.log(`  ✓ 已恢复 ${TABLE}`)
  }
}

function renameTable(from, to) {
  dbExec(`RENAME TABLE \`${from}\` TO \`${to}\``)
}

function countPosts(channelId) {
  return Number(dbExec(`SELECT COUNT(*) FROM posts WHERE channel_id='${channelId}'`).trim())
}

// ---------------------------------------------------------------------------
// 配置（带原状还原）
// ---------------------------------------------------------------------------

async function snapshotConfigs(token) {
  const view = await api('GET', '/admin/config', { token })
  const map = new Map()
  for (const group of view.groups ?? []) {
    for (const item of group.items ?? []) map.set(item.key, item)
  }
  for (const key of [MODE_KEY, ENABLED_KEY]) {
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
  if (cleanup.configs.size > 0) console.log('  ✓ 敏感词配置已按原状还原')
}

// ---------------------------------------------------------------------------
// 清理
// ---------------------------------------------------------------------------

async function runCleanup() {
  console.log('\n清理：')

  // 先保证表回来，再做任何接口调用（表不在时所有发帖都会 503）
  if (cleanup.tableRenamed) {
    try {
      renameTable(BACKUP, TABLE)
      cleanup.tableRenamed = false
      console.log(`  ✓ 已恢复表 ${TABLE}`)
    } catch (error) {
      console.error(`  ! 恢复表失败：${error.message}（请手工 RENAME ${BACKUP} TO ${TABLE}）`)
    }
  }

  if (cleanup.wordId) {
    try {
      await api('DELETE', `/admin/sensitive-words/${cleanup.wordId}`, {
        token: cleanup.adminToken,
        expect: [204, 404],
      })
      console.log('  ✓ 测试词条已删除')
    } catch (error) {
      console.error(`  ! 删除测试词条失败：${error.message}`)
    }
  }

  if (cleanup.configs.size > 0 && cleanup.adminToken) {
    await restoreConfigs()
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
  console.log(`\n敏感词 fail-closed 回归 · ${BASE_URL}`)

  const login = await api('POST', '/auth/login', {
    body: { username: ADMIN_USERNAME, password: ADMIN_PASSWORD },
  })
  const admin = { token: login.accessToken }
  cleanup.adminToken = admin.token
  const me = await api('GET', '/admin/me', { token: admin.token })
  assert(me.admin === true, '需要平台管理员账号（ADMIN_USERNAME / ADMIN_PASSWORD）')

  let owner
  let channel
  let board

  await step('准备：表自愈 + 配置快照 + 频道/板块', async () => {
    healTableIfNeeded()
    await snapshotConfigs(admin.token)

    const username = `wfown_${suffix()}`
    await api('POST', '/auth/register', {
      expect: [201],
      body: { username, password: 'Passw0rd123', nickname: 'wfown' },
    })
    const loginOwner = await api('POST', '/auth/login', { body: { username, password: 'Passw0rd123' } })
    owner = { token: loginOwner.accessToken }
    cleanup.ownerToken = owner.token

    channel = await api('POST', '/channels', {
      token: owner.token,
      expect: [201],
      body: { name: `WordFilter ${suffix()}`, avatarUrl: '/uploads/default-avatar.png', type: 'PUBLIC' },
    })
    cleanup.channelId = channel.id
    board = (await api('GET', `/channels/${channel.id}/boards`)).items[0]
    assert(board, '频道应自带默认板块')

    await setConfig(admin.token, ENABLED_KEY, 'true')
    await setConfig(admin.token, MODE_KEY, 'BLOCK')
  })

  await step('BLOCK + 词库正常：命中即拒（422），内容不入库', async () => {
    const word = `E2E禁词${suffix()}`.slice(0, 20)
    const created = await api('POST', '/admin/sensitive-words', {
      token: admin.token,
      expect: [201],
      body: { word, category: 'CUSTOM', action: 'BLOCK', enabled: true },
    })
    cleanup.wordId = created.id

    const before = countPosts(channel.id)
    const res = await api('POST', `/channels/${channel.id}/posts`, {
      token: owner.token,
      expect: [422],
      raw: true,
      body: { boardId: board.id, content: `这里包含 ${word} 的内容`, contentFormat: 'PLAIN' },
    })
    assert(
      res.data?.error?.code === 'SENSITIVE_WORD_BLOCKED',
      `命中应返回 SENSITIVE_WORD_BLOCKED，实际 ${res.data?.error?.code}`,
    )
    assert(countPosts(channel.id) === before, '被拒的内容不应落库')
  })

  await step('BLOCK + 词库查询异常：fail-closed 拒绝（503 SENSITIVE_WORD_UNAVAILABLE）', async () => {
    // 先创建一条词条：它会 invalidate() 把 60 秒缓存置空，下一次校验必然回源查库。
    // 这样不用等缓存过期，异常窗口只有 1~2 秒。
    const probe = await api('POST', '/admin/sensitive-words', {
      token: admin.token,
      expect: [201],
      body: { word: `E2E探针${suffix()}`.slice(0, 20), category: 'CUSTOM', action: 'REVIEW', enabled: true },
    })
    const probeId = probe.id

    const before = countPosts(channel.id)
    try {
      renameTable(TABLE, BACKUP)
      cleanup.tableRenamed = true

      const res = await api('POST', `/channels/${channel.id}/posts`, {
        token: owner.token,
        expect: [503],
        raw: true,
        body: { boardId: board.id, content: '词库挂掉时这条必须被拒', contentFormat: 'PLAIN' },
      })
      assert(
        res.data?.error?.code === 'SENSITIVE_WORD_UNAVAILABLE',
        `应返回 SENSITIVE_WORD_UNAVAILABLE，实际 ${res.data?.error?.code}`,
      )
      assert(
        (res.data?.error?.message ?? '').includes('校验'),
        `错误信息应说明内容校验不可用，实际「${res.data?.error?.message}」`,
      )
      assert(countPosts(channel.id) === before, 'fail-closed 拒绝的内容不应落库')
    } finally {
      // 无论断言是否失败都要把表改回来，否则整个站点的发帖都会 503
      if (cleanup.tableRenamed) {
        renameTable(BACKUP, TABLE)
        cleanup.tableRenamed = false
      }
      await api('DELETE', `/admin/sensitive-words/${probeId}`, {
        token: admin.token,
        expect: [204, 404],
      }).catch(() => null)
    }
  })

  await step('恢复后同一内容可正常发布（表已复原、缓存回源成功）', async () => {
    assert(!cleanup.tableRenamed, '前置：表应已恢复');
    assert(tableExists(TABLE), `${TABLE} 应存在`)
    assert(!tableExists(BACKUP), `${BACKUP} 不应残留`)

    const post = await api('POST', `/channels/${channel.id}/posts`, {
      token: owner.token,
      expect: [201],
      body: { boardId: board.id, content: '词库恢复后这条可以正常发布', contentFormat: 'PLAIN' },
    })
    assert(post.id, '恢复后发帖应成功')
  })

  const passed = results.filter((item) => item.status === 'pass').length
  const failed = results.filter((item) => item.status === 'fail').length
  const skipped = results.filter((item) => item.status === 'skip').length
  console.log(`\n结果：${passed} 通过 / ${failed} 失败 / ${skipped} 跳过（共 ${results.length} 项）\n`)
  if (failed > 0) process.exitCode = 1
}

// 异常退出也要尽力把表改回来（Ctrl+C / 未捕获异常）
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    if (cleanup.tableRenamed) {
      try {
        renameTable(BACKUP, TABLE)
        console.log(`\n! 收到 ${signal}，已恢复表 ${TABLE}`)
      } catch (error) {
        console.error(`\n! 收到 ${signal}，恢复表失败：${error.message}`)
      }
    }
    process.exit(1)
  })
}

main()
  .catch((error) => {
    console.error(`\n失败：${error.message}`)
    process.exitCode = 1
  })
  .finally(async () => {
    await runCleanup()
  })
