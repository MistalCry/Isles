import { describe, expect, it } from 'vitest'
import enUS from '@/i18n/en-US'
import zhCN from '@/i18n/zh-CN'

/**
 * i18n 守卫测试。
 *
 * 用 Vite 的 `import.meta.glob(..., { query: '?raw' })` 读取源码原文，
 * 不依赖 Node fs（避免为测试引入 node 类型），也能在浏览器模式下运行。
 */
const SOURCES = import.meta.glob(
  ['../**/*.{vue,ts}', '!../i18n/**', '!../test/**', '!**/__tests__/**'],
  { query: '?raw', import: 'default', eager: true },
) as Record<string, string>

/** 允许出现中文的非文案位置（品牌名、i18n 文案本身） */
const ALLOWLIST = ['constants/app.ts']

function leafKeys(obj: Record<string, unknown>, prefix = ''): string[] {
  const keys: string[] = []
  for (const [key, value] of Object.entries(obj)) {
    const full = prefix ? `${prefix}.${key}` : key
    if (value && typeof value === 'object') {
      keys.push(...leafKeys(value as Record<string, unknown>, full))
    } else {
      keys.push(full)
    }
  }
  return keys
}

function leafValues(obj: Record<string, unknown>): string[] {
  const values: string[] = []
  for (const value of Object.values(obj)) {
    if (value && typeof value === 'object') {
      values.push(...leafValues(value as Record<string, unknown>))
    } else {
      values.push(String(value))
    }
  }
  return values
}

const ZH_KEYS = leafKeys(zhCN as unknown as Record<string, unknown>)
const EN_KEYS = leafKeys(enUS as unknown as Record<string, unknown>)

describe('i18n 完整性（防止硬编码文案回潮）', () => {
  it('中英文 key 完全对齐', () => {
    const missingInEn = ZH_KEYS.filter((key) => !EN_KEYS.includes(key))
    const missingInZh = EN_KEYS.filter((key) => !ZH_KEYS.includes(key))
    expect(missingInEn, `英文缺少：${missingInEn.join(', ')}`).toEqual([])
    expect(missingInZh, `中文缺少：${missingInZh.join(', ')}`).toEqual([])
    expect(ZH_KEYS.length).toBeGreaterThan(500)
  })

  it("代码里引用的 t('key') 都存在", () => {
    const used = new Set<string>()
    for (const source of Object.values(SOURCES)) {
      for (const match of source.matchAll(/(?<![A-Za-z0-9_$])t\(\s*'([^']+)'/g)) {
        used.add(match[1])
      }
      for (const match of source.matchAll(/\bt\(\s*"([^"]+)"/g)) {
        used.add(match[1])
      }
    }
    const missing = [...used].filter((key) => !ZH_KEYS.includes(key))
    expect(missing, `未定义的文案 key：${missing.join(', ')}`).toEqual([])
    // 确认扫描确实生效，避免正则失效后「空跑通过」
    expect(used.size).toBeGreaterThan(300)
  })

  it('文案里不残留未转义的 @ 与 |（vue-i18n 语法字符）', () => {
    // @ 是链接语法、| 是复数分隔符：直接出现会让运行时抛
    // `Message compilation error: Invalid linked format`
    const offenders: string[] = []
    for (const [locale, messages] of [
      ['zh-CN', zhCN],
      ['en-US', enUS],
    ] as const) {
      for (const text of leafValues(messages as unknown as Record<string, unknown>)) {
        const stripped = text.replace(/\{'@'\}/g, '').replace(/\{'\|'\}/g, '')
        if (stripped.includes('@') || stripped.includes('|')) {
          offenders.push(`${locale}: ${text}`)
        }
      }
    }
    expect(offenders, `需要转义的文案：\n${offenders.join('\n')}`).toEqual([])
  })

  it('源码里不再有硬编码中文（注释除外）', () => {
    const offenders: string[] = []
    for (const [file, source] of Object.entries(SOURCES)) {
      const rel = file.replace(/^\.\.\//, '')
      if (ALLOWLIST.some((allowed) => rel === allowed)) continue
      source.split('\n').forEach((line: string, index: number) => {
        const trimmed = line.trim()
        if (trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*')) return
        if (trimmed.startsWith('<!--')) return
        if (!/[\u4e00-\u9fa5]/.test(line)) return
        offenders.push(`${rel}:${index + 1} ${trimmed.slice(0, 80)}`)
      })
    }
    expect(offenders, `硬编码中文：\n${offenders.slice(0, 20).join('\n')}`).toEqual([])
    expect(Object.keys(SOURCES).length).toBeGreaterThan(60)
  })

  it('带占位符的文案能正确插值（含 @ 转义）', async () => {
    const { t, setLocale } = await import('@/i18n')
    setLocale('zh-CN')
    expect(t('comment.replyTo', { name: '岛民' })).toBe('回复 @岛民')
    expect(t('post.detail.views', { n: '12' })).toBe('12 浏览')
    setLocale('en-US')
    expect(t('comment.replyTo', { name: 'Islander' })).toBe('Replying to @Islander')
    setLocale('zh-CN')
  })
})
