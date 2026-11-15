// 校验侧边栏「管理」标识可点击并跳转到频道管理中心
import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9339
const ORIGIN = 'http://127.0.0.1:5173'
const API = `${ORIGIN}/api/v1`
const profile = mkdtempSync(join(tmpdir(), 'badge-'))
const chrome = spawn(CHROME, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, '--no-first-run', '--disable-gpu', '--window-size=1440,1000'], { stdio: 'ignore' })
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
  await sleep(1500)
  await evaluate(`(async () => {
    await fetch('${API}/auth/login', { method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'admin_demo', password: 'Passw0rd123' }) })
    return 1
  })()`, true)
  await send('Page.reload')
  await sleep(5000)
  console.log('url before:', await evaluate('location.pathname'))
  console.log('badges:', await evaluate(`document.querySelectorAll('.channel-item__manage').length`))
  const clicked = await evaluate(`(() => {
    const btn = document.querySelector('.channel-item__manage')
    if (!btn) return 'no-badge'
    btn.click()
    return 'clicked'
  })()`)
  console.log('click:', clicked)
  await sleep(3500)
  console.log('url after:', await evaluate('location.pathname'))
  ws.close()
} finally {
  chrome.kill()
  await sleep(300)
  try { rmSync(profile, { recursive: true, force: true }) } catch { /* ignore */ }
}
