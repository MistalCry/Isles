// 诊断脚本：热门页推荐区（刷新 / 加入）与登录态恢复
import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9337
const ORIGIN = 'http://127.0.0.1:5173'
const API = `${ORIGIN}/api/v1`
const profile = mkdtempSync(join(tmpdir(), 'hot-'))
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
    if (r.result?.exceptionDetails) return `ERR:${r.result.exceptionDetails.exception?.description?.slice(0, 120)}`
    return r.result?.result?.value
  }

  await send('Runtime.enable')
  await send('Network.enable')
  await sleep(1500)

  console.log('login:', await evaluate(`(async () => {
    const r = await fetch('${API}/auth/login', { method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'admin_demo', password: 'Passw0rd123' }) })
    return r.status
  })()`, true))
  console.log('refresh:', await evaluate(`(async () => {
    const r = await fetch('${API}/auth/refresh', { method: 'POST', credentials: 'include' })
    return r.status
  })()`, true))
  console.log('cookie:', await evaluate('document.cookie'))

  await send('Page.navigate', { url: `${ORIGIN}/hot` })
  await sleep(6000)
  console.log('url:', await evaluate('location.pathname'))
  console.log('panel:', await evaluate(`!!document.querySelector('.recommendation-panel')`))
  console.log('items:', await evaluate(`document.querySelectorAll('.recommendation-item').length`))
  console.log('join buttons:', await evaluate(`[...document.querySelectorAll('.recommendation-item button')].map(b => b.textContent.trim()).join('|')`))

  // 刷新按钮
  const before = events.filter((e) => e.method === 'Network.requestWillBeSent' && e.params.request.url.includes('/hot/recommendations')).length
  await evaluate(`(() => {
    const head = document.querySelector('.recommendation-panel__head')
    const btn = head?.querySelector('button')
    if (!btn) return 'no-refresh'
    btn.click()
    return 'clicked'
  })()`)
  await sleep(2500)
  const after = events.filter((e) => e.method === 'Network.requestWillBeSent' && e.params.request.url.includes('/hot/recommendations')).length
  console.log('recommendation requests before/after refresh:', before, after)

  console.log('join btn html:', await evaluate(`(() => {
    const btn = [...document.querySelectorAll('.recommendation-item button')].find(b => b.textContent.trim() === '加入')
    return btn ? btn.outerHTML.slice(0, 200) + ' || loading=' + btn.classList.contains('is-loading') + ' disabled=' + btn.disabled : 'none'
  })()`))

  // 加入按钮
  const joinResult = await evaluate(`(() => {
    const btn = [...document.querySelectorAll('.recommendation-item button')].find(b => b.textContent.trim() === '加入')
    if (!btn) return 'no-join-button'
    const rect = btn.getBoundingClientRect()
    const top = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2)
    btn.click()
    return JSON.stringify({ disabled: btn.disabled, rect: [Math.round(rect.x), Math.round(rect.y)], topElement: top?.className || top?.tagName })
  })()`)
  console.log('join click:', joinResult)
  await sleep(2500)
  console.log('toast:', await evaluate(`[...document.querySelectorAll('.el-message')].map(n => n.textContent.trim()).join('|')`))
  console.log('join requests:', events.filter((e) => e.method === 'Network.requestWillBeSent' && /\/channels\/.+\/join/.test(e.params.request.url)).map((e) => e.params.request.url).join(' '))

  ws.close()
} finally {
  chrome.kill()
  await sleep(300)
  try { rmSync(profile, { recursive: true, force: true }) } catch { /* ignore */ }
}
