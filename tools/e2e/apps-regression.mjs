#!/usr/bin/env node
/**
 * 频道应用 / 语音房 / 活动回归（V10，第 14 轮）。
 *
 * A. 应用：CRUD、批量排序、频道可见（游客可读）、无权限用户不可写。
 * B. 语音房：开房 → 两人进房 → 并发抢同一麦位（只有一个成功，另一个 409）→ 静音 → 下麦 → 关房；
 *    STOMP 信令：两个客户端订阅 /topic/voice/{id}/signal，一端发 offer，另一端收到且 from 正确；
 *    安全边界：非成员订阅/发送被拒（NOT_IN_ROOM）、关房后发送被拒（ROOM_CLOSED）。
 * D. 级联：频道解散后 channel_apps / voice_rooms / channel_activities 不留孤儿行（需本机 mysql 客户端）。
 * C. 活动：创建 → 发布 → 报名 → 取消 → 人数上限 422 → 开始前 30 分钟提醒只发一次。
 *
 * 提醒用例需要把扫描间隔调短（默认 10 分钟一次）：
 *   启动 8082 时加 `--app.apps.activity-reminder-cron=0/10 * * * * *`（每 10 秒扫一次）。
 *   脚本默认等 25 秒（≥2 个扫描周期），确认只发了一条通知。
 *
 * 运行：BASE_URL=http://127.0.0.1:8082/api/v1 node tools/e2e/apps-regression.mjs
 */

import { execFileSync } from 'node:child_process'

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1'
const WS_URL = process.env.WS_URL || BASE_URL.replace(/^http/, 'ws').replace(/\/api\/v1\/?$/, '') + '/ws'
/** 等几个扫描周期再断言「只提醒一次」 */
const REMINDER_WAIT_MS = Number(process.env.REMINDER_WAIT_MS || 25_000)

const results = []
const cleanup = { channelId: null, ownerToken: null, roomId: null, activityId: null }

/**
 * 直连数据库校验（级联删除只能从表里看，接口对已解散频道一律 404）。
 * 走本机 mysql 客户端；没有客户端时相关用例记为跳过并说明。
 */
// 用 E2E_ 前缀：外层 shell 里常有项目无关的 DB_* 变量，直接读会被带偏
const DB = {
  host: process.env.E2E_DB_HOST || '127.0.0.1',
  port: process.env.E2E_DB_PORT || '3306',
  name: process.env.E2E_DB_NAME || 'my_social_channel',
  user: process.env.E2E_DB_USERNAME || 'isles_app',
  password: process.env.E2E_DB_PASSWORD || 'IslesDev2026a7',
}

function dbRows(sql) {
  let output
  try {
    output = execFileSync(
      'mysql',
      ['-h', DB.host, '-P', DB.port, '-u', DB.user, `-p${DB.password}`, DB.name, '-N', '-B', '-e', sql],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
    )
  } catch (error) {
    throw new SkipCase(
      `无法连接数据库校验（缺 mysql 客户端或凭据不对）：${String(error.message).split('\n')[0]}`,
    )
  }
  return output
    .trim()
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
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

/** 用例内部判定「依赖未就绪/已知缺陷」时抛出，交给 step 记为跳过 */
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

/** 极简 STOMP 客户端：CONNECT / SUBSCRIBE / SEND / 收帧 */
function connectStomp(token) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(WS_URL)
    const frames = []
    const waiters = []
    let connected = false
    const timer = setTimeout(() => reject(new Error('WebSocket 连接超时')), 8000)

    const flush = () => {
      for (let i = waiters.length - 1; i >= 0; i -= 1) {
        const waiter = waiters[i]
        const index = frames.findIndex(waiter.match)
        if (index >= 0) {
          const [frame] = frames.splice(index, 1)
          waiters.splice(i, 1)
          clearTimeout(waiter.timer)
          waiter.resolve(frame)
        }
      }
    }

    socket.onopen = () => {
      socket.send(
        `CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer ${token}\nheart-beat:0,0\n\n\u0000`,
      )
    }
    socket.onerror = () => reject(new Error('WebSocket 错误（检查 /ws 端点）'))
    socket.onclose = () => {
      if (!connected) reject(new Error('WebSocket 在 CONNECTED 前被关闭（鉴权失败？）'))
    }
    socket.onmessage = (event) => {
      for (const chunk of String(event.data).split('\u0000')) {
        if (!chunk.trim()) continue
        const [head, ...rest] = chunk.split('\n\n')
        const lines = head.split('\n')
        const command = lines.shift()
        const headers = {}
        for (const line of lines) {
          const idx = line.indexOf(':')
          if (idx > 0) headers[line.slice(0, idx)] = line.slice(idx + 1)
        }
        const frame = { command, headers, body: rest.join('\n\n') }
        if (command === 'CONNECTED') {
          connected = true
          clearTimeout(timer)
          resolve({
            /** 收到的 ERROR 帧（拦截器拒绝订阅/发送时会下发）；用 getter 保证取到实时值 */
            get errors() {
              return frames.filter((frame) => frame.command === 'ERROR').map((frame) => frame.body)
            },
            subscribe(destination) {
              socket.send(`SUBSCRIBE\nid:sub-${destination}-${Date.now()}\ndestination:${destination}\n\n\u0000`)
            },
            send(destination, payload) {
              const body = JSON.stringify(payload)
              socket.send(
                `SEND\ndestination:${destination}\ncontent-type:application/json\ncontent-length:${Buffer.byteLength(body)}\n\n${body}\u0000`,
              )
            },
            next(match, timeoutMs = 8000) {
              const existing = frames.findIndex(match)
              if (existing >= 0) return Promise.resolve(frames.splice(existing, 1)[0])
              return new Promise((res, rej) => {
                const waiter = { match, resolve: res }
                waiter.timer = setTimeout(() => {
                  const i = waiters.indexOf(waiter)
                  if (i >= 0) waiters.splice(i, 1)
                  rej(new Error('等待 STOMP 消息超时'))
                }, timeoutMs)
                waiters.push(waiter)
              })
            },
            close() {
              try {
                socket.send('DISCONNECT\n\n\u0000')
              } catch {
                // ignore
              }
              socket.close()
            },
          })
          return
        }
        frames.push(frame)
        flush()
      }
    }
  })
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function runCleanup() {
  console.log('\n清理：')
  if (cleanup.roomId) {
    try {
      await api('POST', `/voice-rooms/${cleanup.roomId}/close`, {
        token: cleanup.ownerToken,
        expect: [200, 404, 409],
      })
      console.log('  ✓ 语音房已关闭')
    } catch (error) {
      console.error(`  ! 关房失败：${error.message}`)
    }
  }
  if (cleanup.activityId) {
    try {
      await api('DELETE', `/activities/${cleanup.activityId}`, {
        token: cleanup.ownerToken,
        expect: [204, 404, 422],
      })
      console.log('  ✓ 活动已删除')
    } catch (error) {
      console.error(`  ! 删除活动失败：${error.message}`)
    }
  }
  if (cleanup.channelId) {
    // 先删应用再解散：频道解散不会级联清理 channel_apps，留着就是孤儿数据
    try {
      const apps = await api('GET', `/channels/${cleanup.channelId}/apps?includeDisabled=true`, {
        token: cleanup.ownerToken,
        expect: [200, 404],
      })
      for (const app of Array.isArray(apps) ? apps : []) {
        await api('DELETE', `/channels/${cleanup.channelId}/apps/${app.id}`, {
          token: cleanup.ownerToken,
          expect: [204, 404],
        })
      }
      if (Array.isArray(apps) && apps.length > 0) console.log(`  ✓ 测试应用已删除 ${apps.length} 个`)
    } catch (error) {
      console.error(`  ! 删除测试应用失败：${error.message}`)
    }
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
  console.log(`\n群岛应用/语音房/活动回归 · ${BASE_URL}\n`)

  const health = await fetch(`${BASE_URL.replace(/\/api\/v1$/, '')}/actuator/health`).catch(() => null)
  if (!health || !health.ok) {
    console.error('后端不可用，请先启动后端')
    process.exit(1)
  }

  let owner
  let member
  let outsider
  let channel

  await step('准备：房主 + 成员 + 非成员 + 公开频道', async () => {
    owner = await newUser('appsown')
    member = await newUser('appsmem')
    outsider = await newUser('appsout')
    channel = await api('POST', '/channels', {
      token: owner.token,
      expect: [201],
      body: { name: `Apps Audit ${suffix()}`, avatarUrl: '/uploads/default-avatar.png', type: 'PUBLIC' },
    })
    cleanup.channelId = channel.id
    cleanup.ownerToken = owner.token
    await api('POST', `/channels/${channel.id}/join`, { token: member.token })
  })

  if (!channel) {
    blockedReason = '前置准备失败（账号/频道未创建），常见原因：注册限流 429，请用 RATE_LIMIT_ENABLED=false 启动后端'
  }

  // ------------------------------------------------------------------
  // A. 应用
  // ------------------------------------------------------------------
  let appVoice
  let appCustom

  await step('应用：创建两个应用（语音房 + 自定义）', async () => {
    appVoice = await api('POST', `/channels/${channel.id}/apps`, {
      token: owner.token,
      expect: [201],
      body: { appType: 'VOICE_ROOM', name: `语音房${suffix()}`, description: '一起听歌', enabled: true },
    })
    appCustom = await api('POST', `/channels/${channel.id}/apps`, {
      token: owner.token,
      expect: [201],
      body: { appType: 'CUSTOM', name: `自定义${suffix()}`, description: '外链应用', enabled: true },
    })
    assert(appVoice.appType === 'VOICE_ROOM', `appType 应为 VOICE_ROOM，实际 ${appVoice.appType}`)
    assert(appCustom.appType === 'CUSTOM', `appType 应为 CUSTOM，实际 ${appCustom.appType}`)
  })

  await step('应用：列表按 sortOrder 升序，游客也能看到（频道详情可见）', async () => {
    const list = await api('GET', `/channels/${channel.id}/apps`)
    assert(Array.isArray(list), '应用列表应为数组')
    assert(list.length >= 2, `列表应至少 2 个应用，实际 ${list.length}`)
    const orders = list.map((item) => item.sortOrder)
    assert(
      orders.every((value, index) => index === 0 || value >= orders[index - 1]),
      `列表应按 sortOrder 升序，实际 ${orders.join(',')}`,
    )
    // 游客（无 token）也能读：频道详情页对未登录用户展示应用入口
    const guest = await api('GET', `/channels/${channel.id}/apps`)
    assert(guest.length === list.length, '游客看到的应用数量应与成员一致')
  })

  await step('应用：批量重排后顺序变化', async () => {
    const reordered = await api('PUT', `/channels/${channel.id}/apps/order`, {
      token: owner.token,
      body: { appIds: [appCustom.id, appVoice.id] },
    })
    const ids = reordered.map((item) => item.id)
    assert(ids[0] === appCustom.id, `重排后第一个应为自定义应用，实际 ${ids.join(',')}`)
    assert(reordered[0].sortOrder === 0, `首个应用 sortOrder 应为 0，实际 ${reordered[0].sortOrder}`)

    const list = await api('GET', `/channels/${channel.id}/apps`)
    assert(list[0].id === appCustom.id, '列表应反映新的排序')
  })

  await step('应用：更新与禁用，默认列表不含禁用项', async () => {
    const updated = await api('PUT', `/channels/${channel.id}/apps/${appVoice.id}`, {
      token: owner.token,
      body: { appType: 'VOICE_ROOM', name: '语音房（已改名）', enabled: false },
    })
    assert(updated.enabled === false, '更新后应处于禁用状态')
    assert(updated.name.includes('已改名'), `名称应更新，实际 ${updated.name}`)

    const visible = await api('GET', `/channels/${channel.id}/apps`)
    assert(!visible.some((item) => item.id === appVoice.id), '默认列表不应包含禁用应用')

    const all = await api('GET', `/channels/${channel.id}/apps?includeDisabled=true`, { token: owner.token })
    assert(all.some((item) => item.id === appVoice.id), 'includeDisabled=true 应能看到禁用应用')

    // 恢复启用，后续语音房用例要用
    await api('PUT', `/channels/${channel.id}/apps/${appVoice.id}`, {
      token: owner.token,
      body: { appType: 'VOICE_ROOM', name: '语音房（已改名）', enabled: true },
    })
  })

  await step('应用：无频道管理权限的用户不能创建（403）', async () => {
    await api('POST', `/channels/${channel.id}/apps`, {
      token: member.token,
      expect: [403],
      body: { appType: 'CUSTOM', name: '越权应用' },
    })
  })

  // ------------------------------------------------------------------
  // B. 语音房
  // ------------------------------------------------------------------
  let room

  await step('语音房：开房成功，麦位全空', async () => {
    room = await api('POST', `/channels/${channel.id}/voice-rooms`, {
      token: owner.token,
      expect: [201],
      body: { appId: appVoice.id, title: `房间${suffix()}`, maxSeats: 4 },
    })
    cleanup.roomId = room.id
    assert(room.status === 'OPEN', `新房间应为 OPEN，实际 ${room.status}`)
    assert(room.maxSeats === 4, `麦位数应为 4，实际 ${room.maxSeats}`)
    assert((room.seats ?? []).every((seat) => !seat.userId), '新房间麦位应全空')
  })

  await step('语音房：两人进房，非成员被拒', async () => {
    await api('POST', `/voice-rooms/${room.id}/join`, { token: member.token })
    const detail = await api('GET', `/voice-rooms/${room.id}`, { token: member.token })
    assert(detail.id === room.id, '成员应能进房')
    await api('POST', `/voice-rooms/${room.id}/join`, { token: outsider.token, expect: [403] })
  })

  await step('语音房：并发抢同一麦位，只有一个成功', async () => {
    const attempts = await Promise.all([
      api('POST', `/voice-rooms/${room.id}/seats`, {
        token: owner.token,
        expect: [200, 409],
        body: { seatIndex: 0 },
      }),
      api('POST', `/voice-rooms/${room.id}/seats`, {
        token: member.token,
        expect: [200, 409],
        body: { seatIndex: 0 },
      }),
    ])
    const ok = attempts.filter((item) => item && item.seats)
    // 只有一个请求拿到 RoomView（另一个抛 409 被 expect 接受，返回 undefined）
    assert(ok.length === 1, `同一麦位应只有一个成功，实际 ${ok.length} 个成功`)
    const detail = await api('GET', `/voice-rooms/${room.id}`)
    const seat0 = (detail.seats ?? []).find((seat) => seat.seatIndex === 0)
    assert(seat0?.userId, '麦位 0 应被占用')
  })

  await step('语音房：静音与下麦', async () => {
    const detail = await api('GET', `/voice-rooms/${room.id}`)
    const occupied = (detail.seats ?? []).find((seat) => seat.userId)
    assert(occupied, '应有一个已占用麦位')

    const seatOwnerToken = occupied.userId === owner.id ? owner.token : member.token
    const muted = await api('POST', `/voice-rooms/${room.id}/seats/${occupied.seatIndex}/mute`, {
      token: seatOwnerToken,
      body: { muted: true },
    })
    const mutedSeat = (muted.seats ?? []).find((seat) => seat.seatIndex === occupied.seatIndex)
    assert(mutedSeat?.muted === true, '静音后该麦位 muted 应为 true')

    const left = await api('DELETE', `/voice-rooms/${room.id}/seats/me`, { token: seatOwnerToken })
    const afterLeave = (left.seats ?? []).find((seat) => seat.seatIndex === occupied.seatIndex)
    assert(!afterLeave?.userId, '下麦后该麦位应空出')
  })

  await step('语音房：STOMP 信令转发（A 发 offer，B 收到且 from 正确）', async () => {
    const clientA = await connectStomp(owner.token)
    const clientB = await connectStomp(member.token)
    try {
      clientB.subscribe(`/topic/voice/${room.id}/signal`)
      clientA.subscribe(`/topic/voice/${room.id}/signal`)
      // 等订阅生效（STOMP 无 ACK，给服务端一点时间建立订阅）
      await sleep(300)
      assert(
        clientA.errors.length === 0 && clientB.errors.length === 0,
        `成员订阅不应收到 ERROR 帧，实际 ${[...clientA.errors, ...clientB.errors].join(' | ')}`,
      )

      clientA.send(`/app/voice/${room.id}/signal`, { type: 'offer', sdp: 'v=0-offer-e2e' })
      const frame = await clientB.next(
        (item) => item.command === 'MESSAGE' && item.body.includes('v=0-offer-e2e'),
        6000,
      )
      const event = JSON.parse(frame.body)
      assert(event.type === 'VOICE_SIGNAL', `事件类型应为 VOICE_SIGNAL，实际 ${event.type}`)
      // 信封：{ id, type, ts, version, data: { roomId, from, data } }
      assert(event.data?.from === owner.id, `from 应为发送者 ${owner.id}，实际 ${event.data?.from}`)
      assert(event.data?.roomId === room.id, '事件应带 roomId')
      assert(event.data?.data?.type === 'offer', '信令内容应原样透传 type=offer')
      assert(event.data?.data?.sdp === 'v=0-offer-e2e', 'SDP 应原样透传（服务端不解析）')

      // 反向：B 发 answer，A 收到
      clientB.send(`/app/voice/${room.id}/signal`, { type: 'answer', sdp: 'v=0-answer-e2e' })
      const back = await clientA.next(
        (item) => item.command === 'MESSAGE' && item.body.includes('v=0-answer-e2e'),
        8000,
      )
      const backEvent = JSON.parse(back.body)
      assert(backEvent.data?.from === member.id, '反向信令的 from 应为成员 id')
      assert(
        clientA.errors.length === 0 && clientB.errors.length === 0,
        '整个转发过程都不应出现 ERROR 帧',
      )
    } finally {
      clientA.close()
      clientB.close()
    }
  })

  await step('语音房：非成员订阅/发送被拒（NOT_IN_ROOM，安全边界）', async () => {
    const outsider = await newUser('vout')
    // 订阅：不该拿到房间主题的任何信令（含 SDP / ICE）
    const subClient = await connectStomp(outsider.token)
    try {
      subClient.subscribe(`/topic/voice/${room.id}/signal`)
      const error = await subClient.next((item) => item.command === 'ERROR', 4000)
      assert(
        error.headers.code === 'NOT_IN_ROOM',
        `非成员订阅应被拒且 code=NOT_IN_ROOM，实际 code=${error.headers.code} body=${error.body}`,
      )
    } finally {
      subClient.close()
    }

    // 发送：单独一条连接，避免上一条被 ERROR 关闭后误判
    const sendClient = await connectStomp(outsider.token)
    try {
      sendClient.send(`/app/voice/${room.id}/signal`, { type: 'offer', sdp: 'v=0-outsider' })
      const error = await sendClient.next((item) => item.command === 'ERROR', 4000)
      assert(
        error.headers.code === 'NOT_IN_ROOM',
        `非成员发送应被拒且 code=NOT_IN_ROOM，实际 code=${error.headers.code} body=${error.body}`,
      )
    } finally {
      sendClient.close()
    }
  })

  await step('语音房：关房后不可再进，麦位清空', async () => {
    const closed = await api('POST', `/voice-rooms/${room.id}/close`, { token: owner.token })
    cleanup.roomId = null
    assert(closed.status === 'CLOSED', `关房后状态应为 CLOSED，实际 ${closed.status}`)
    await api('POST', `/voice-rooms/${room.id}/join`, { token: member.token, expect: [409, 404] })
    const list = await api('GET', `/channels/${channel.id}/voice-rooms`)
    assert(!list.some((item) => item.id === room.id), '开启中列表不应再包含已关闭房间')
  })

  await step('语音房：关房后信令订阅与发送都被拒（ROOM_CLOSED）', async () => {
    // 关房后即使原本在房里，也不能再收发信令（否则旧连接能继续占用信令通道）
    const subClient = await connectStomp(owner.token)
    try {
      subClient.subscribe(`/topic/voice/${room.id}/signal`)
      const error = await subClient.next((item) => item.command === 'ERROR', 4000)
      assert(
        error.headers.code === 'ROOM_CLOSED',
        `关房后订阅应被拒且 code=ROOM_CLOSED，实际 code=${error.headers.code} body=${error.body}`,
      )
    } finally {
      subClient.close()
    }

    const sendClient = await connectStomp(owner.token)
    try {
      sendClient.send(`/app/voice/${room.id}/signal`, { type: 'offer', sdp: 'v=0-closed' })
      const error = await sendClient.next((item) => item.command === 'ERROR', 4000)
      assert(
        error.headers.code === 'ROOM_CLOSED',
        `关房后发送应被拒且 code=ROOM_CLOSED，实际 code=${error.headers.code} body=${error.body}`,
      )
    } finally {
      sendClient.close()
    }
  })

  // ------------------------------------------------------------------
  // C. 活动
  // ------------------------------------------------------------------
  let activity

  await step('活动：创建（容量 1，20 分钟后开始）', async () => {
    const startAt = new Date(Date.now() + 20 * 60 * 1000).toISOString()
    activity = await api('POST', `/channels/${channel.id}/activities`, {
      token: owner.token,
      expect: [201],
      body: {
        title: `活动${suffix()}`,
        description: 'E2E 活动',
        startAt,
        location: '群岛灯塔',
        capacity: 1,
      },
    })
    cleanup.activityId = activity.id
    assert(activity.status === 'DRAFT', `新活动应为 DRAFT，实际 ${activity.status}`)
    assert(activity.capacity === 1, `容量应为 1，实际 ${activity.capacity}`)
  })

  await step('活动：发布后成员报名成功，重复报名幂等', async () => {
    const published = await api('POST', `/activities/${activity.id}/publish`, { token: owner.token })
    assert(published.status === 'PUBLISHED', `发布后应为 PUBLISHED，实际 ${published.status}`)

    const signed = await api('POST', `/activities/${activity.id}/signup`, { token: member.token })
    assert(signed.signedUp === true, '报名后 signedUp 应为 true')
    assert(signed.goingCount === 1, `报名人数应为 1，实际 ${signed.goingCount}`)

    const again = await api('POST', `/activities/${activity.id}/signup`, { token: member.token })
    assert(again.goingCount === 1, `重复报名不应重复计数，实际 ${again.goingCount}`)
  })

  await step('活动：超过人数上限报名被拒（422 ACTIVITY_FULL）', async () => {
    const error = await api('POST', `/activities/${activity.id}/signup`, {
      token: owner.token,
      expect: [422],
    })
    assert(error?.error?.code === 'ACTIVITY_FULL', `错误码应为 ACTIVITY_FULL，实际 ${error?.error?.code}`)
  })

  await step('活动：取消报名后人数回落', async () => {
    const cancelled = await api('DELETE', `/activities/${activity.id}/signup`, { token: member.token })
    assert(cancelled.signedUp === false, '取消后 signedUp 应为 false')
    assert(cancelled.goingCount === 0, `取消后人数应为 0，实际 ${cancelled.goingCount}`)
  })

  await step('活动：开始前 30 分钟提醒只发一次', async () => {
    // 重新报名，确保有收件人
    await api('POST', `/activities/${activity.id}/signup`, { token: member.token })

    // 等 ≥2 个扫描周期（8082 需以 --app.apps.activity-reminder-cron=0/10 * * * * * 启动）
    await sleep(REMINDER_WAIT_MS)

    const notifications = await api('GET', '/notifications?type=SYSTEM&limit=50', { token: member.token })
    const hits = (notifications.items ?? []).filter(
      (item) => item.payload?.activityId === activity.id && (item.title ?? '').includes('活动即将开始'),
    )
    assert(hits.length > 0, '应在开始前收到活动提醒')
    assert(hits.length === 1, `提醒只应发一次，实际 ${hits.length} 条`)
    assert((hits[0].content ?? '').includes(activity.title), '提醒正文应包含活动标题')
  })

  await step('活动：取消活动后不可再报名', async () => {
    const cancelled = await api('POST', `/activities/${activity.id}/cancel`, { token: owner.token })
    assert(cancelled.status === 'CANCELLED', `取消后状态应为 CANCELLED，实际 ${cancelled.status}`)
    await api('POST', `/activities/${activity.id}/signup`, { token: member.token, expect: [422, 409] })
  })

  // ------------------------------------------------------------------
  // D. 级联删除（K2）
  // ------------------------------------------------------------------
  await step('级联：频道解散后应用/语音房/活动不留孤儿行', async () => {
    // 单独造一个频道，避免影响前面用例的断言对象
    const tempChannel = await api('POST', '/channels', {
      token: owner.token,
      expect: [201],
      body: { name: `Cascade ${suffix()}`, avatarUrl: '/uploads/default-avatar.png', type: 'PUBLIC' },
    })
    const tempChannelId = tempChannel.id
    try {
      await api('POST', `/channels/${tempChannelId}/apps`, {
        token: owner.token,
        expect: [201],
        body: { appType: 'CUSTOM', name: `级联应用${suffix()}`, description: '级联校验', enabled: true },
      })
      const tempRoom = await api('POST', `/channels/${tempChannelId}/voice-rooms`, {
        token: owner.token,
        expect: [201],
        body: { title: `级联房间${suffix()}`, maxSeats: 4 },
      })
      const tempActivity = await api('POST', `/channels/${tempChannelId}/activities`, {
        token: owner.token,
        expect: [201],
        body: {
          title: `级联活动${suffix()}`,
          description: 'E2E 级联',
          startAt: new Date(Date.now() + 3 * 60 * 60 * 1000).toISOString(),
          location: '群岛码头',
          capacity: 5,
        },
      })

      // 解散前先确认三类行都在（否则「解散后为 0」不能说明级联生效）
      const before = dbRows(`SELECT
          (SELECT COUNT(*) FROM channel_apps WHERE channel_id='${tempChannelId}'),
          (SELECT COUNT(*) FROM voice_rooms WHERE channel_id='${tempChannelId}'),
          (SELECT COUNT(*) FROM channel_activities WHERE channel_id='${tempChannelId}')`)[0]
      const [appsBefore, roomsBefore, actsBefore] = before.split('\t').map(Number)
      assert(
        appsBefore >= 1 && roomsBefore >= 1 && actsBefore >= 1,
        `解散前应各有一行，实际 apps=${appsBefore} rooms=${roomsBefore} activities=${actsBefore}`,
      )

      await api('POST', `/channels/${tempChannelId}/dissolve`, { token: owner.token, expect: [204, 200] })

      const after = dbRows(`SELECT
          (SELECT COUNT(*) FROM channel_apps WHERE channel_id='${tempChannelId}'),
          (SELECT COUNT(*) FROM voice_rooms WHERE channel_id='${tempChannelId}'),
          (SELECT COUNT(*) FROM channel_activities WHERE channel_id='${tempChannelId}'),
          (SELECT COUNT(*) FROM voice_room_seats s JOIN voice_rooms r ON r.id=s.room_id WHERE r.channel_id='${tempChannelId}')`)[0]
      const [appsAfter, roomsAfter, actsAfter, seatsAfter] = after.split('\t').map(Number)
      assert(
        appsAfter === 0 && roomsAfter === 0 && actsAfter === 0,
        `解散后应无孤儿行，实际 apps=${appsAfter} rooms=${roomsAfter} activities=${actsAfter}`,
      )
      assert(seatsAfter === 0, `麦位也应一并清理，实际 ${seatsAfter} 行`)
      // 房间对象与活动对象确实建过：避免「接口没建成功所以表里为 0」的假通过
      assert(tempRoom.status === 'OPEN' && tempActivity.status === 'DRAFT', '房间/活动创建状态应正常')
    } finally {
      // 已解散则无需再处理；异常路径下尽力清理
      await api('POST', `/channels/${tempChannelId}/dissolve`, {
        token: owner.token,
        expect: [204, 200, 403, 404, 409],
      }).catch(() => {})
    }
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
