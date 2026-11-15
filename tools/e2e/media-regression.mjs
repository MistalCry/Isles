#!/usr/bin/env node
/**
 * 上传格式回归（P1）：heic / avif / mov 各传一次成功且能回读；非法类型仍被拒；
 * ffmpeg 不可用时走「原图直出 / 无封面」降级而不是报错。
 *
 * 夹具来源：
 *   - avif / mov：优先用本机 ffmpeg 现场生成（真实可解码文件，能验证尺寸/时长）；
 *     ffmpeg 不可用时退化为最小 ISO BMFF 头（只验证「格式识别 + 上传 + 回读」）；
 *   - heic：ffmpeg 不支持 heic 封装，固定用最小 ftyp(heic) 头 + 填充字节，
 *     验证的是「品牌识别正确、不被误判成 mp4、上传与回读可用」。
 *
 * 两种运行模式：
 *   1) 正常模式（ffmpeg 可用）：E2E_FFMPEG_MODE=auto
 *      node tools/e2e/media-regression.mjs
 *   2) 降级模式（后端以 `--app.media.ffmpeg=/nonexistent/ffmpeg` 启动）：
 *      E2E_FFMPEG_MODE=missing node tools/e2e/media-regression.mjs
 *      断言 heic/avif 仍上传成功但尺寸为空、无缩略图；mov 仍成功但无时长/封面。
 *
 * 注册限流 5 次/小时/IP，重复跑请用 RATE_LIMIT_ENABLED=false 启动后端。
 */

import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const BASE_URL = process.env.BASE_URL || 'http://127.0.0.1:8080/api/v1'
const ORIGIN = BASE_URL.replace(/\/api\/v1\/?$/, '')
/** auto：本机 ffmpeg 可用；missing：后端 ffmpeg 不可用（降级路径） */
const FFMPEG_MODE = process.env.E2E_FFMPEG_MODE || 'auto'

const results = []

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

async function step(name, fn) {
  try {
    await fn()
    results.push({ name, status: 'pass' })
    console.log(`  ✓ ${name}`)
  } catch (error) {
    results.push({ name, status: 'fail', error: error.message })
    console.error(`  ✗ ${name}\n    ${error.message}`)
  }
}

function suffix() {
  return `${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`
}

/** 构造最小 ISO BMFF ftyp box：size + 'ftyp' + major brand + minor version + 兼容 brand */
function ftyp(majorBrand, compatible = []) {
  const brands = [majorBrand, ...compatible]
  const size = 16 + brands.length * 4
  const buffer = Buffer.alloc(Math.max(size, 32))
  buffer.writeUInt32BE(size, 0)
  buffer.write('ftyp', 4, 'ascii')
  buffer.write(majorBrand, 8, 'ascii')
  buffer.writeUInt32BE(0, 12)
  brands.forEach((brand, index) => buffer.write(brand, 16 + index * 4, 'ascii'))
  return buffer
}

/** 用 ffmpeg 生成真实媒体；失败返回 null（调用方退化为最小头） */
function ffmpegImage(format, args) {
  const dir = mkdtempSync(join(tmpdir(), 'isles-media-'))
  const target = join(dir, `sample.${format}`)
  try {
    execFileSync('ffmpeg', ['-y', '-loglevel', 'error', ...args, target], { stdio: 'ignore' })
    return readFileSync(target)
  } catch {
    return null
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

function buildFixtures() {
  const avif = ffmpegImage('avif', ['-f', 'lavfi', '-i', 'color=c=red:s=64x48', '-frames:v', '1'])
  const mov = ffmpegImage('mov', [
    '-f', 'lavfi', '-i', 'color=c=blue:s=64x48:d=1',
    '-c:v', 'libx264', '-pix_fmt', 'yuv420p',
  ])
  return {
    avif: { bytes: avif ?? ftyp('avif', ['mif1']), real: Boolean(avif), mime: 'image/avif', filename: 'sample.avif' },
    mov: { bytes: mov ?? ftyp('qt  ', ['qt  ']), real: Boolean(mov), mime: 'video/quicktime', filename: 'sample.mov' },
    // ffmpeg 不支持 heic 封装：固定最小头，验证品牌识别与上传/回读
    heic: { bytes: ftyp('heic', ['mif1', 'heic']), real: false, mime: 'image/heic', filename: 'sample.heic' },
    junk: { bytes: Buffer.from('this is definitely not an image, just plain text padding....', 'utf8'), mime: 'text/plain', filename: 'fake.heic' },
  }
}

async function upload(token, fixture, declaredType) {
  const form = new FormData()
  form.append('file', new Blob([fixture.bytes], { type: fixture.mime }), fixture.filename)
  form.append('type', declaredType)
  const response = await fetch(`${BASE_URL}/media/upload`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
    body: form,
  })
  const text = await response.text()
  let data = text
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    // 保留原文
  }
  return { status: response.status, data }
}

async function readBack(url) {
  const response = await fetch(`${ORIGIN}${url}`)
  const buffer = Buffer.from(await response.arrayBuffer())
  return { status: response.status, bytes: buffer.length }
}

async function main() {
  console.log(`\n群岛上传格式回归 · ${BASE_URL} · ffmpeg=${FFMPEG_MODE}\n`)

  const health = await fetch(`${ORIGIN}/actuator/health`).catch(() => null)
  if (!health || !health.ok) {
    console.error('后端不可用，请先启动后端')
    process.exit(1)
  }

  const fixtures = buildFixtures()
  let user

  await step('准备：注册上传账号', async () => {
    const username = `media_${suffix()}`
    const password = 'Passw0rd123'
    await api('POST', '/auth/register', { expect: [201], body: { username, password, nickname: 'media' } })
    const login = await api('POST', '/auth/login', { body: { username, password } })
    user = { id: login.user.id, token: login.accessToken }
  })

  await step('heic 上传成功（识别为图片，不被误判成 mp4）且能回读', async () => {
    const { status, data } = await upload(user.token, fixtures.heic, 'image')
    assert(status === 201, `heic 上传应 201，实际 ${status} ${JSON.stringify(data)}`)
    assert(data.mediaType === 'IMAGE', `heic 应识别为 IMAGE，实际 ${data.mediaType}`)
    assert(data.url?.endsWith('.heic'), `heic 存储扩展名应保留，实际 ${data.url}`)
    const read = await readBack(data.url)
    assert(read.status === 200, `回读 heic 应 200，实际 ${read.status}`)
    assert(read.bytes === fixtures.heic.bytes.length, `回读字节数应与上传一致，实际 ${read.bytes}`)
  })

  await step('avif 上传成功且能回读', async () => {
    const { status, data } = await upload(user.token, fixtures.avif, 'image')
    assert(status === 201, `avif 上传应 201，实际 ${status} ${JSON.stringify(data)}`)
    assert(data.mediaType === 'IMAGE', `avif 应识别为 IMAGE，实际 ${data.mediaType}`)
    assert(data.url?.endsWith('.avif'), `avif 存储扩展名应保留，实际 ${data.url}`)
    const read = await readBack(data.url)
    assert(read.status === 200, `回读 avif 应 200，实际 ${read.status}`)
    assert(read.bytes === fixtures.avif.bytes.length, `回读字节数应与上传一致，实际 ${read.bytes}`)
    if (FFMPEG_MODE === 'auto' && fixtures.avif.real) {
      assert(data.width === 64 && data.height === 48, `avif 应解出 64x48，实际 ${data.width}x${data.height}`)
      assert(Boolean(data.thumbUrl), 'avif 应生成缩略图')
    }
  })

  await step('mov 上传成功且能回读', async () => {
    const { status, data } = await upload(user.token, fixtures.mov, 'video')
    assert(status === 201, `mov 上传应 201，实际 ${status} ${JSON.stringify(data)}`)
    assert(data.mediaType === 'VIDEO', `mov 应识别为 VIDEO，实际 ${data.mediaType}`)
    assert(data.url?.endsWith('.mov'), `mov 存储扩展名应保留，实际 ${data.url}`)
    const read = await readBack(data.url)
    assert(read.status === 200, `回读 mov 应 200，实际 ${read.status}`)
    assert(read.bytes === fixtures.mov.bytes.length, `回读字节数应与上传一致，实际 ${read.bytes}`)
    if (FFMPEG_MODE === 'auto' && fixtures.mov.real) {
      assert(data.durationMs > 0, `mov 应探测出时长，实际 ${data.durationMs}`)
      assert(Boolean(data.thumbUrl), 'mov 应生成首帧封面')
    }
  })

  await step('非法类型被拒：文本冒充 heic 返回 400', async () => {
    const { status, data } = await upload(user.token, fixtures.junk, 'image')
    assert(status === 400, `非法类型应 400，实际 ${status}`)
    assert(data?.error?.code === 'MEDIA_INVALID_TYPE', `错误码应为 MEDIA_INVALID_TYPE，实际 ${data?.error?.code}`)
  })

  await step('声明类型与真实类型不符被拒：mov 按 image 上传返回 400', async () => {
    const { status, data } = await upload(user.token, fixtures.mov, 'image')
    assert(status === 400, `类型不符应 400，实际 ${status}`)
    assert(data?.error?.code === 'MEDIA_INVALID_TYPE', `错误码应为 MEDIA_INVALID_TYPE，实际 ${data?.error?.code}`)
  })

  await step('非法 type 参数被拒（只能是 image / video）', async () => {
    const { status, data } = await upload(user.token, fixtures.heic, 'audio')
    assert(status === 400, `type=audio 应 400，实际 ${status}`)
    assert(data?.error, '应返回错误信封')
  })

  await step(
    FFMPEG_MODE === 'missing' ? 'ffmpeg 不可用：heic/avif 降级为原图直出（无尺寸/缩略图）' : 'ffmpeg 可用：图片解码路径正常',
    async () => {
      const heic = await upload(user.token, fixtures.heic, 'image')
      assert(heic.status === 201, `ffmpeg 不可用时 heic 仍应上传成功，实际 ${heic.status}`)
      const avif = await upload(user.token, fixtures.avif, 'image')
      assert(avif.status === 201, `ffmpeg 不可用时 avif 仍应上传成功，实际 ${avif.status}`)

      if (FFMPEG_MODE === 'missing') {
        for (const [name, res] of [['heic', heic], ['avif', avif]]) {
          assert(res.data.width == null && res.data.height == null, `${name} 降级时宽高应为空，实际 ${res.data.width}x${res.data.height}`)
          assert(res.data.thumbUrl == null, `${name} 降级时不应有缩略图，实际 ${res.data.thumbUrl}`)
          assert(Boolean(res.data.url), `${name} 降级时仍应返回可访问 url`)
        }
        const mov = await upload(user.token, fixtures.mov, 'video')
        assert(mov.status === 201, `ffmpeg 不可用时 mov 仍应上传成功，实际 ${mov.status}`)
        assert(mov.data.durationMs == null, `mov 降级时时长应为空，实际 ${mov.data.durationMs}`)
        assert(mov.data.thumbUrl == null, 'mov 降级时不应有封面')
      } else {
        assert(Boolean(heic.data.url) && Boolean(avif.data.url), '图片应返回可访问 url')
      }
    },
  )

  const passed = results.filter((item) => item.status === 'pass').length
  const failed = results.filter((item) => item.status === 'fail').length
  console.log(`\n结果：${passed} 通过 / ${failed} 失败（共 ${results.length} 项）\n`)
  if (failed > 0) {
    console.log('失败项：')
    results.filter((item) => item.status === 'fail').forEach((item) => console.log(`  - ${item.name}\n    ${item.error}`))
    console.log('')
  }
  return failed
}

try {
  const failed = await main()
  process.exit(failed > 0 ? 1 : 0)
} catch (error) {
  console.error(`\n回归脚本异常：${error.message}\n`)
  process.exit(1)
}
