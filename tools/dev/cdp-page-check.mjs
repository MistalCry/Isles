// 单页渲染检查：登录后打开指定路径，统计裁剪组件数量并输出异常
import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9336
const ORIGIN = 'http://127.0.0.1:5173'
const API = `${ORIGIN}/api/v1`
const target = process.argv[2]
const selector = process.argv[3] || '.image-crop-upload'

const profile = mkdtempSync(join(tmpdir(), 'pc-'))
const chrome = spawn(CHROME, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, '--no-first-run', '--disable-gpu'], { stdio: 'ignore' })
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
  const events = []
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data)
    if (m.id && pending.has(m.id)) {
      pending.get(m.id)(m)
      pending.delete(m.id)
    } else if (m.method) events.push(m)
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
  await sleep(2000)
  await evaluate(`(async () => {
    await fetch('${API}/auth/login', { method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'admin_demo', password: 'Passw0rd123' }) })
    return 1
  })()`, true)

  await send('Page.navigate', { url: `${ORIGIN}/feed` })
  await sleep(4000)
  await send('Page.navigate', { url: ORIGIN + target })
  await sleep(6000)

  console.log('title:', await evaluate('document.title'))
  console.log('url:', await evaluate('location.pathname'))
  console.log(`${selector} count:`, await evaluate(`document.querySelectorAll('${selector}').length`))
  console.log('body:', (await evaluate('document.body.innerText'))?.slice(0, 150).replace(/\n/g, ' | '))

  const exceptions = events.filter((e) => e.method === 'Runtime.exceptionThrown')
    .map((e) => (e.params.exceptionDetails.exception?.description ?? e.params.exceptionDetails.text).slice(0, 200))
  const consoleErrors = events.filter((e) => e.method === 'Runtime.consoleAPICalled' && e.params.type === 'error')
    .map((e) => e.params.args.map((a) => a.value ?? a.description ?? '').join(' ').slice(0, 200))
  console.log('exceptions:', exceptions.length ? exceptions.join(' || ') : 'none')
  console.log('console errors:', consoleErrors.length ? consoleErrors.join(' || ') : 'none')
  ws.close()
} finally {
  chrome.kill()
  await sleep(300)
  try { rmSync(profile, { recursive: true, force: true }) } catch { /* ignore */ }
}
