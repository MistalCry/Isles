// 浏览器级 UI 冒烟：发帖（快捷 + 富文本）、频道设置（加入方式）、未加入评论提示
import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9334
const ORIGIN = 'http://127.0.0.1:5173'
const API = `${ORIGIN}/api/v1`
const CHANNEL_ID = process.argv[2]
const POST_ID = process.argv[3]
const USER = process.argv[4] || 'admin_demo'
const PASS = process.argv[5] || 'Passw0rd123'

if (!CHANNEL_ID) {
  console.error('用法: node cdp-ui-check.mjs <channelId> [postId] [username] [password]')
  process.exit(1)
}

const profile = mkdtempSync(join(tmpdir(), 'isles-ui-'))
const chrome = spawn(CHROME, [
  '--headless=new',
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${profile}`,
  '--no-first-run',
  '--no-default-browser-check',
  '--disable-gpu',
  '--window-size=1440,1000',
], { stdio: 'ignore' })

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function waitDevtools() {
  for (let i = 0; i < 40; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/version`)
      if (r.ok) return r.json()
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
  const target = await (await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent(ORIGIN + '/login')}`, { method: 'PUT' })).json()
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((res, rej) => { ws.addEventListener('open', res); ws.addEventListener('error', rej) })
  const { send, events } = cdp(ws)
  await send('Page.enable')
  await send('Runtime.enable')
  await send('Network.enable')
  await sleep(2500)

  const evaluate = async (expression, awaitPromise = false) => {
    const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise })
    if (result.exceptionDetails) {
      throw new Error(result.exceptionDetails.exception?.description ?? 'evaluate failed')
    }
    return result.result?.value
  }

  // 1) 用接口登录，写入 HttpOnly refresh cookie，再让 SPA 启动时恢复会话
  const login = await evaluate(`(async () => {
    const r = await fetch('${API}/auth/login', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: '${USER}', password: '${PASS}' }),
    })
    return r.status
  })()`, true)
  console.log('login status:', login)

  // 2) 打开频道页
  await send('Page.navigate', { url: `${ORIGIN}/channels/${CHANNEL_ID}` })
  await sleep(5000)

  const title = await evaluate('document.title')
  console.log('page title:', title)

  // 3) 快捷发帖
  const quick = await evaluate(`(() => {
    const input = document.querySelector('.post-composer__quick-input input, .post-composer__quick-input textarea')
    if (!input) return 'no-quick-input'
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
    setter.call(input, 'UI smoke quick post ' + Date.now())
    input.dispatchEvent(new Event('input', { bubbles: true }))
    const buttons = [...document.querySelectorAll('.post-composer button')]
    const publish = buttons.find((b) => b.textContent.trim() === '发布')
    if (!publish) return 'no-publish-button'
    publish.click()
    return 'clicked'
  })()`)
  console.log('quick post:', quick)
  await sleep(3500)
  const quickToast = await evaluate(`[...document.querySelectorAll('.el-message')].map(n => n.textContent.trim()).join(' | ')`)
  console.log('quick toast:', quickToast || '(none)')

  // 4) 长文（富文本）
  const openFull = await evaluate(`(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === '发长文')
    if (!btn) return 'no-full-button'
    btn.click()
    return 'opened'
  })()`)
  console.log('open full composer:', openFull)
  await sleep(1500)
  const editorResult = await evaluate(`(() => {
    const editor = document.querySelector('.rich-editor__content')
    if (!editor) return 'no-editor'
    editor.focus()
    document.execCommand('insertHTML', false, '<h2>UI 富文本标题</h2><p>正文<strong>加粗</strong>内容</p>')
    editor.dispatchEvent(new Event('input', { bubbles: true }))
    return editor.innerHTML.slice(0, 120)
  })()`)
  console.log('editor html:', editorResult)
  const publishFull = await evaluate(`(() => {
    const dialog = document.querySelector('.el-dialog')
    if (!dialog) return 'no-dialog'
    const btn = [...dialog.querySelectorAll('button')].find((b) => b.textContent.trim() === '发布')
    if (!btn) return 'no-publish'
    btn.click()
    return 'clicked'
  })()`)
  console.log('publish full:', publishFull)
  await sleep(4000)
  const fullToast = await evaluate(`[...document.querySelectorAll('.el-message')].map(n => n.textContent.trim()).join(' | ')`)
  console.log('full toast:', fullToast || '(none)')

  // 5) 频道设置：切换加入方式并保存，再刷新确认持久化
  const openSettings = await evaluate(`(() => {
    const btn = document.querySelector('button[aria-label="频道设置"]')
    if (!btn) return 'no-settings-button'
    btn.click()
    return 'opened'
  })()`)
  console.log('open settings:', openSettings)
  await sleep(1500)
  const switchType = await evaluate(`(() => {
    const radios = [...document.querySelectorAll('.el-dialog .el-radio')]
    const target = radios.find((r) => r.textContent.includes('私密频道'))
    if (!target) return 'no-radio:' + radios.map(r => r.textContent.trim()).join('/')
    target.click()
    return 'clicked'
  })()`)
  console.log('switch join mode:', switchType)
  await sleep(500)
  const saveProfile = await evaluate(`(() => {
    const dialog = document.querySelector('.el-dialog')
    const btn = [...dialog.querySelectorAll('button')].find((b) => b.textContent.trim() === '保存资料')
    if (!btn) return 'no-save'
    btn.click()
    return 'saved'
  })()`)
  console.log('save profile:', saveProfile)
  await sleep(3000)
  const saveToast = await evaluate(`[...document.querySelectorAll('.el-message')].map(n => n.textContent.trim()).join(' | ')`)
  console.log('save toast:', saveToast || '(none)')

  await send('Page.reload')
  await sleep(5000)
  const persisted = await evaluate(`(async () => {
    const r = await fetch('${API}/channels/${CHANNEL_ID}', { credentials: 'include' })
    const j = await r.json()
    return j.type + '/' + j.visibility
  })()`, true)
  console.log('persisted type/visibility:', persisted)

  // 还原为公开频道
  await evaluate(`(async () => {
    const token = await (await fetch('${API}/auth/login', {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: '${USER}', password: '${PASS}' }),
    })).json()
    await fetch('${API}/channels/${CHANNEL_ID}', {
      method: 'PATCH',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token.accessToken },
      body: JSON.stringify({ type: 'PUBLIC', visibility: 'PUBLIC_VISIBLE' }),
    })
    return 'restored'
  })()`, true)

  // 6) 未加入频道的用户：评论区应显示加入提示
  if (POST_ID) {
    await evaluate(`(async () => {
      const suffix = Date.now().toString(36)
      const username = 'uicheck_' + suffix
      await fetch('${API}/auth/register', {
        method: 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password: 'Passw0rd123', nickname: 'UICheck' }),
      })
      await fetch('${API}/auth/logout', { method: 'POST', credentials: 'include' })
      await fetch('${API}/auth/login', {
        method: 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password: 'Passw0rd123' }),
      })
      return username
    })()`, true)
    await send('Page.navigate', { url: `${ORIGIN}/posts/${POST_ID}` })
    await sleep(5000)
    const joinTip = await evaluate(`(() => {
      const tip = document.querySelector('.comment-section__join-tip')
      const composer = document.querySelector('.comment-composer')
      return JSON.stringify({ tip: tip?.textContent?.trim().slice(0, 40) ?? null, hasComposer: Boolean(composer) })
    })()`)
    console.log('non-member comment area:', joinTip)
  }

  const consoleErrors = events
    .filter((e) => e.method === 'Runtime.consoleAPICalled' && e.params.type === 'error')
    .map((e) => e.params.args.map((a) => a.value ?? a.description ?? '').join(' ').slice(0, 200))
  const pageErrors = events
    .filter((e) => e.method === 'Runtime.exceptionThrown')
    .map((e) => (e.params.exceptionDetails.exception?.description ?? e.params.exceptionDetails.text).slice(0, 300))
  const badRequests = events
    .filter((e) => e.method === 'Network.responseReceived' && e.params.response.status >= 500)
    .map((e) => `${e.params.response.status} ${e.params.response.url}`)

  console.log('--- console errors ---')
  consoleErrors.slice(-10).forEach((t) => console.log(' ', t))
  console.log('--- page exceptions ---')
  pageErrors.slice(-10).forEach((t) => console.log(' ', t))
  console.log('--- 5xx ---')
  badRequests.slice(-10).forEach((t) => console.log(' ', t))

  ws.close()
} finally {
  chrome.kill()
  await sleep(500)
  try { rmSync(profile, { recursive: true, force: true }) } catch { /* ignore */ }
}
