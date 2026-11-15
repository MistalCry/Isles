// 打开指定路径并截图（登录后），用于人工核对界面
import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9338
const ORIGIN = 'http://127.0.0.1:5173'
const API = `${ORIGIN}/api/v1`
const target = process.argv[2]
const outFile = process.argv[3] || 'E:/AI/AiProject/MySocialChannel2/.run/shot.png'

const profile = mkdtempSync(join(tmpdir(), 'shot-'))
const chrome = spawn(CHROME, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, '--no-first-run', '--disable-gpu', '--window-size=1440,1100', '--hide-scrollbars'], { stdio: 'ignore' })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

try {
  for (let i = 0; i < 40; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/version`)
      if (r.ok) break
    } catch { /* retry */ }
    await sleep(250)
  }
  const t = await (await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent(ORIGIN + '/login')}`, { method: 'PUT' })).json()
  const ws = new WebSocket(t.webSocketDebuggerUrl)
  await new Promise((r) => ws.addEventListener('open', r))
  let id = 0
  const pending = new Map()
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data)
    if (m.id && pending.has(m.id)) {
      pending.get(m.id)(m)
      pending.delete(m.id)
    }
  })
  const send = (method, params = {}) => new Promise((res) => {
    const i = ++id
    pending.set(i, res)
    ws.send(JSON.stringify({ id: i, method, params }))
  })
  const evaluate = async (expression, awaitPromise = false) => {
    const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise })
    return r.result?.result?.value
  }

  await send('Runtime.enable')
  await send('Page.enable')
  await send('Network.enable')
  const events = []
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data)
    if (m.method) events.push(m)
  })
  await sleep(1500)
  await evaluate(`(async () => {
    await fetch('${API}/auth/login', { method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'admin_demo', password: 'Passw0rd123' }) })
    return 1
  })()`, true)

  // 让 SPA 先完成一次会话恢复（reload 后 bootstrap 会用 refresh cookie 换 token），再整页跳转
  await sleep(2000)
  await send('Page.reload')
  await sleep(5000)
  // 走真实点击路径：侧边栏频道 → 频道设置 → 进入管理中心 → 频道助手设置
  const clickByText = async (selector, text) => evaluate(`(() => {
    const nodes = [...document.querySelectorAll(${JSON.stringify(selector)})]
    const hit = nodes.find((n) => n.textContent.trim().includes(${JSON.stringify(text)}))
    if (!hit) return 'not-found:' + ${JSON.stringify(text)}
    hit.click()
    return 'clicked:' + ${JSON.stringify(text)}
  })()`)

  console.log('open channel:', await clickByText('.channel-item', 'Assistant Demo'))
  await sleep(3500)
  console.log('open settings:', await evaluate(`(() => { const b = document.querySelector('button[aria-label="频道设置"]'); if (!b) return 'no-btn'; b.click(); return 'ok' })()`))
  await sleep(2000)
  console.log('go manage center:', await clickByText('.el-dialog button', '进入频道管理中心'))
  await sleep(4000)
  console.log('open assistant tab:', await clickByText('.manage-sidebar a, .manage-sidebar button, .manage-sidebar li', '频道助手设置'))
  await sleep(5000)
  console.log('url:', await evaluate('location.pathname'))

  console.log('body:', (await evaluate('document.body.innerText'))?.slice(0, 160).replace(/\n/g, ' | '))
  const authCalls = events
    .filter((e) => e.method === 'Network.responseReceived' && e.params.response.url.includes('/api/v1/auth/'))
    .map((e) => `${e.params.response.status} ${e.params.response.url.split('/api/v1')[1]}`)
  console.log('auth calls:', authCalls.join(' ; '))
  const exceptions = events.filter((e) => e.method === 'Runtime.exceptionThrown')
    .map((e) => (e.params.exceptionDetails.exception?.description ?? e.params.exceptionDetails.text).slice(0, 150))
  console.log('exceptions:', exceptions.length ? exceptions.join(' || ') : 'none')

  const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
  writeFileSync(outFile, Buffer.from(shot.result.data, 'base64'))
  console.log('saved:', outFile)
  ws.close()
} finally {
  chrome.kill()
  await sleep(300)
  try { rmSync(profile, { recursive: true, force: true }) } catch { /* ignore */ }
}
