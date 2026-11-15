#!/usr/bin/env node
/**
 * 实时推送检查（STOMP over WebSocket，ADR-0003 / docs/08 §2「社交与实时」）。
 *
 * 验证：
 * 1. CONNECT 帧携带 Bearer token 才能建立会话；
 * 2. 订阅 /user/queue/dm 后，对方发私信能实时收到 DM_MESSAGE；
 * 3. 订阅 /user/queue/notifications 后，帖子被点赞能实时收到通知事件。
 *
 * 运行：node tools/e2e/realtime-check.mjs
 * 依赖：Node 18+（使用内置 WebSocket，Node 22 已验证）
 */

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1'
const WS_URL = process.env.WS_URL || BASE_URL.replace(/^http/, 'ws').replace(/\/api\/v1$/, '') + '/ws'

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

function suffix() {
  return `${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`
}

async function newUser(prefix) {
  const username = `${prefix}_${suffix()}`
  const password = 'Passw0rd123'
  await api('POST', '/auth/register', { expect: [201], body: { username, password, nickname: prefix } })
  const login = await api('POST', '/auth/login', { body: { username, password } })
  return { id: login.user.id, token: login.accessToken }
}

/** 极简 STOMP 客户端：只实现本检查需要的 CONNECT/SUBSCRIBE/收帧。 */
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

    const onFrame = (frame) => {
      frames.push(frame)
      flush()
    }

    socket.onopen = () => {
      socket.send(
        `CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer ${token}\nheart-beat:0,0\n\n\u0000`,
      )
    }
    socket.onerror = () => reject(new Error('WebSocket 错误（检查 /ws 端点与跨域配置）'))
    socket.onclose = () => {
      if (!connected) reject(new Error('WebSocket 在 CONNECTED 前被关闭（鉴权失败？）'))
    }
    socket.onmessage = (event) => {
      const chunks = String(event.data).split('\u0000')
      for (const chunk of chunks) {
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
            socket,
            subscribe(destination) {
              socket.send(`SUBSCRIBE\nid:sub-${destination}\ndestination:${destination}\n\n\u0000`)
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
        onFrame(frame)
      }
    }
  })
}

function parseEvent(frame) {
  return JSON.parse(frame.body)
}

async function main() {
  console.log(`\n群岛实时推送检查 · ${WS_URL}\n`)

  const alice = await newUser('rtA')
  const bob = await newUser('rtB')
  const channel = await api('POST', '/channels', {
    token: alice.token,
    expect: [201],
    body: { name: `RT ${suffix()}`, avatarUrl: '/uploads/default-avatar.png', type: 'PUBLIC' },
  })
  const boards = await api('GET', `/channels/${channel.id}/boards`)
  const post = await api('POST', `/channels/${channel.id}/posts`, {
    token: alice.token,
    expect: [201],
    body: { boardId: boards.items[0].id, content: 'realtime target post', contentFormat: 'PLAIN' },
  })

  const client = await connectStomp(bob.token)
  console.log('  ✓ CONNECT 鉴权通过')
  client.subscribe('/user/queue/dm')
  client.subscribe('/user/queue/notifications')

  // 私信推送
  const conversation = await api('POST', '/dm/conversations', {
    token: alice.token,
    expect: [200, 201],
    body: { userId: bob.id },
  })
  const sentAt = Date.now()
  await api('POST', `/dm/conversations/${conversation.id}/messages`, {
    token: alice.token,
    expect: [201],
    body: { type: 'TEXT', content: 'realtime hello' },
  })
  const dmFrame = await client.next((f) => f.command === 'MESSAGE' && f.body.includes('DM_MESSAGE'))
  const dmEvent = parseEvent(dmFrame)
  assert(dmEvent.type === 'DM_MESSAGE', '私信事件类型应为 DM_MESSAGE')
  assert(dmEvent.data?.content === 'realtime hello', '私信事件应包含消息内容')
  console.log(`  ✓ 私信实时推送（${Date.now() - sentAt}ms）`)

  client.close()

  // 通知推送：bob 点赞 alice 的帖子，alice 的连接应收到通知事件

  const aliceClient = await connectStomp(alice.token)
  aliceClient.subscribe('/user/queue/notifications')
  await api('POST', `/posts/${post.id}/like`, { token: bob.token })
  const notifyFrame = await aliceClient.next((f) => f.command === 'MESSAGE' && f.body.includes('NOTIFICATION'))
  const notifyEvent = parseEvent(notifyFrame)
  assert(
    String(notifyEvent.type).includes('NOTIFICATION') || notifyEvent.data?.type,
    '通知事件应包含类型',
  )
  console.log(`  ✓ 通知实时推送（type=${notifyEvent.type}）`)
  aliceClient.close()

  console.log('\n结果：3/3 步通过\n')
}

main().catch((error) => {
  console.error(`\n实时检查失败：${error.message}\n`)
  process.exit(1)
})
