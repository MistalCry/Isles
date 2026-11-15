// 用真实 Chrome（CDP）复现注册流程，捕获网络失败与控制台错误
import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9333
const PAGE = 'http://127.0.0.1:5173/register?redirect=/ai'
const profile = mkdtempSync(join(tmpdir(), 'isles-cdp-'))

const chrome = spawn(CHROME, [
  '--headless=new',
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${profile}`,
  '--no-first-run',
  '--no-default-browser-check',
  '--disable-gpu',
  '--window-size=1280,900',
], { stdio: 'ignore' })

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function waitDevtools() {
  for (let i = 0; i < 40; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/version`)
      if (r.ok) return await r.json()
    } catch { /* retry */ }
    await sleep(250)
  }
  throw new Error('Chrome DevTools 未就绪')
}

function cdp(ws) {
  let id = 0
  const pending = new Map()
  const events = []
  ws.addEventListener('message', (e) => {
    const msg = JSON.parse(e.data)
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id)
      pending.delete(msg.id)
      msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result)
    } else if (msg.method) {
      events.push(msg)
    }
  })
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const msgId = ++id
    pending.set(msgId, { resolve, reject })
    ws.send(JSON.stringify({ id: msgId, method, params }))
  })
  return { send, events }
}

try {
  await waitDevtools()
  const target = await (await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent(PAGE)}`, { method: 'PUT' })).json()
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((res, rej) => { ws.addEventListener('open', res); ws.addEventListener('error', rej) })
  const { send, events } = cdp(ws)

  await send('Page.enable')
  await send('Runtime.enable')
  await send('Network.enable')
  await send('Log.enable')
  await send('Page.navigate', { url: PAGE })
  await sleep(6000)

  const fill = `(() => {
    const setValue = (input, value) => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
      setter.call(input, value)
      input.dispatchEvent(new Event('input', { bubbles: true }))
      input.dispatchEvent(new Event('change', { bubbles: true }))
    }
    const inputs = [...document.querySelectorAll('.el-input__inner')]
    if (inputs.length < 4) return 'inputs=' + inputs.length
    const name = 'cdp' + Date.now().toString(36)
    setValue(inputs[0], name)
    setValue(inputs[1], 'CDP')
    setValue(inputs[2], 'Passw0rd123')
    setValue(inputs[3], 'Passw0rd123')
    const box = document.querySelector('.el-checkbox__input')
    if (box) box.click()
    return 'filled:' + name
  })()`
  const filled = await send('Runtime.evaluate', { expression: fill, returnByValue: true })
  console.log('fill:', filled.result?.value)

  await sleep(500)
  const clicked = await send('Runtime.evaluate', {
    expression: `(() => { const b = document.querySelector('.auth-card__submit'); if (!b) return 'no-button'; b.click(); return 'clicked' })()`,
    returnByValue: true,
  })
  console.log('click:', clicked.result?.value)
  await sleep(6000)

  const state = await send('Runtime.evaluate', {
    expression: `JSON.stringify({
      url: location.href,
      alert: document.querySelector('.el-alert__title')?.textContent?.trim() || null,
      toast: [...document.querySelectorAll('.el-message')].map(n => n.textContent.trim()),
      readyState: document.readyState
    })`,
    returnByValue: true,
  })
  console.log('page:', state.result?.value)

  const failures = events.filter((e) => e.method === 'Network.loadingFailed')
    .map((e) => `${e.params.type} ${e.params.errorText} canceled=${e.params.canceled}`)
  const badResponses = events.filter((e) => e.method === 'Network.responseReceived' && e.params.response.status >= 400)
    .map((e) => `${e.params.response.status} ${e.params.response.url}`)
  const consoleErrors = events.filter((e) => e.method === 'Runtime.consoleAPICalled' && e.params.type === 'error')
    .map((e) => e.params.args.map((a) => a.value ?? a.description ?? '').join(' ').slice(0, 200))
  const logErrors = events.filter((e) => e.method === 'Log.entryAdded' && e.params.entry.level === 'error')
    .map((e) => `${e.params.entry.text} ${e.params.entry.url ?? ''}`.slice(0, 200))

  console.log('--- network failures ---'); failures.slice(-15).forEach((f) => console.log(' ', f))
  console.log('--- http >=400 ---'); badResponses.slice(-15).forEach((f) => console.log(' ', f))
  console.log('--- console errors ---'); consoleErrors.slice(-10).forEach((f) => console.log(' ', f))
  console.log('--- log errors ---'); logErrors.slice(-10).forEach((f) => console.log(' ', f))
  ws.close()
} finally {
  chrome.kill()
  await sleep(500)
  try { rmSync(profile, { recursive: true, force: true }) } catch { /* ignore */ }
}
