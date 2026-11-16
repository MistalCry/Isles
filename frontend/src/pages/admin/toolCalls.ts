/**
 * 运行日志里的工具调用解析（`agent_runs.tool_calls_json`）。
 *
 * <p>后端当前写入的是 `{ action, args, summary, outputId }`；联网搜索（`web_search`）与
 * 抓页面（`fetch_page`）上线后，结果结构还可能带 `results` 数组、`chars` 字段。
 * 所以这里只做「宽松归一」：
 * <ol>
 *   <li>先按常见字段名取值（camelCase / snake_case 都认）；</li>
 *   <li>取不到再从 `summary` 文本里正则兜底；</li>
 *   <li>都不命中就保留原文，由页面折叠展示，保证信息不丢。</li>
 * </ol>
 */

export interface ToolCallView {
  /** 动作/工具名，如 web_search、fetch_page；解析不出时为空串 */
  name: string
  query: string | null
  freshness: string | null
  url: string | null
  /** 命中条数；null = 日志里没记 */
  hitCount: number | null
  /** 最多 MAX_TITLES 条标题 */
  titles: string[]
  /** 抓取字数；null = 日志里没记 */
  charCount: number | null
  summary: string | null
  outputId: string | null
  /** 原始对象，页面折叠展示 */
  raw: unknown
}

/** 页面只展示前 3 条标题，避免日志抽屉被搜索结果撑爆 */
export const MAX_TITLES = 3

type Raw = Record<string, unknown>

function isRaw(value: unknown): value is Raw {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function text(value: unknown): string | null {
  if (typeof value === 'string') return value.trim() || null
  if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  return null
}

function firstText(...values: unknown[]): string | null {
  for (const value of values) {
    const found = text(value)
    if (found) return found
  }
  return null
}

function parseJson(value: string): unknown {
  const trimmed = value.trim()
  if (!trimmed || !/^[[{]/.test(trimmed)) return null
  try {
    return JSON.parse(trimmed)
  } catch {
    return null
  }
}

/** 参数可能是对象，也可能是模型原样给出的 JSON 字符串 */
function firstRaw(...values: unknown[]): Raw | null {
  for (const value of values) {
    if (isRaw(value)) return value
    if (typeof value === 'string') {
      const parsed = parseJson(value)
      if (isRaw(parsed)) return parsed
    }
  }
  return null
}

function countOf(...values: unknown[]): number | null {
  for (const value of values) {
    if (typeof value === 'number' && Number.isFinite(value)) return Math.max(0, Math.round(value))
    if (typeof value === 'string' && /^\d+$/.test(value.trim())) return Number(value.trim())
  }
  return null
}

const RESULT_KEYS = ['results', 'hits', 'items', 'pages', 'list', 'data', 'result', 'output']

/** 在结果对象里往下找「带标题/链接」的数组，最多钻 3 层 */
function resultEntries(value: unknown, depth = 0): Raw[] {
  if (depth > 3) return []
  if (Array.isArray(value)) {
    const rows = value.filter(isRaw)
    return rows.filter((row) => firstText(row.title, row.name, row.headline, row.url, row.link, row.href))
  }
  if (!isRaw(value)) return []
  for (const key of RESULT_KEYS) {
    const found = resultEntries(value[key], depth + 1)
    if (found.length > 0) return found
  }
  return []
}

function entryTitle(entry: Raw): string | null {
  return firstText(entry.title, entry.name, entry.headline, entry.url, entry.link, entry.href)
}

const BULLET = /^(?:[-*•]|\d+[.、)])\s*/

/**
 * summary 文本兜底：从列表行里抠标题。
 *
 * <p>只认四种形态：markdown 链接 `- [标题](url)`、后端当前的
 * `- [引擎] 标题 | url`、`标题=xxx` 的键值行，以及普通 `- xxx` 列表行——
 * 缩进的摘要行与「（5 条）」这类句子不算标题。
 */
function titlesFromSummary(summary: string): string[] {
  const titles: string[] = []
  for (const rawLine of summary.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line) continue
    const candidate = titleOfLine(line)
    if (!candidate) continue
    const value = candidate.trim()
    if (value && value.length <= 120) titles.push(value)
    if (titles.length >= MAX_TITLES) break
  }
  return titles
}

function titleOfLine(line: string): string | null {
  const linked = line.match(/\[([^\]]+)\]\(/)
  if (linked) return linked[1]
  const labeled = line.match(/\u6807\u9898[=:：]\s*(.+?)(?=\s+\S+=|$)/)
  if (labeled) return labeled[1]
  if (!BULLET.test(line)) return null
  // `- [bing] 标题 | https://…`：去掉引擎前缀与末尾 url
  const body = line.replace(BULLET, '').replace(/^\[[^\]]+\]\s*/, '')
  const pipe = body.lastIndexOf(' | ')
  return pipe > 0 ? body.slice(0, pipe) : body
}

/**
 * 命中 6 条 / 共 6 条 / 找到 6 条 / （6 条）/ 6 results。
 * 第三项是后端 `web_search` 当前的 summary 头「联网搜索「q」（6 条）：」。
 */
const HIT_PATTERNS = [
  /(?:\u547d\u4e2d|\u5171|\u627e\u5230)\s*(\d+)\s*(?:\u6761|\u4e2a)/,
  /(\d+)\s*\u6761/,
  /(\d+)\s*(?:results?|hits?)/i,
]

/** 抓取 1234 字 / 1234 字符 / 1234 chars */
const CHAR_PATTERNS = [/(\d+)\s*(?:\u5b57\u7b26?|chars?)/i]

function matchCount(source: string | null, patterns: RegExp[]): number | null {
  if (!source) return null
  for (const pattern of patterns) {
    const matched = source.match(pattern)
    if (matched) {
      const parsed = Number(matched[1])
      if (Number.isFinite(parsed)) return parsed
    }
  }
  return null
}

/** 正文存在字段里时，字数就是它的长度 */
function textLengthOf(value: Raw | null): number | null {
  if (!value) return null
  for (const key of ['text', 'content', 'body', 'markdown']) {
    const field = value[key]
    if (typeof field === 'string' && field.trim()) return field.trim().length
  }
  return null
}

/**
 * `fetch_page` 的 summary 形如「页面正文（url）：\n正文…」，
 * 后端没有单独记字数，这里用首行之后的正文长度兜底。
 */
function bodyLength(summary: string | null): number | null {
  if (!summary) return null
  const index = summary.indexOf('\n')
  if (index < 0) return null
  const body = summary.slice(index + 1).trim()
  return body ? body.length : null
}

function blank(raw: unknown, summary: string | null): ToolCallView {
  return {
    name: '',
    query: null,
    freshness: null,
    url: null,
    hitCount: null,
    titles: [],
    charCount: null,
    summary,
    outputId: null,
    raw,
  }
}

function toView(item: unknown): ToolCallView | null {
  if (typeof item === 'string') {
    const summary = item.trim()
    return summary ? blank(item, summary) : null
  }
  if (!isRaw(item)) return null

  const fn = isRaw(item.function) ? item.function : null
  const args = firstRaw(item.args, item.input, item.arguments, item.params, item.parameters, fn?.arguments)
  const result = isRaw(item.result) ? item.result : isRaw(item.output) ? item.output : null
  const summary = firstText(
    item.summary,
    item.summaryText,
    item.summary_text,
    item.message,
    typeof item.result === 'string' ? item.result : null,
    typeof item.output === 'string' ? item.output : null,
    result?.summary,
  )
  const entries = resultEntries(item)
  const fromEntries = entries.map(entryTitle).filter((title): title is string => Boolean(title))
  const titles = (fromEntries.length > 0 ? fromEntries : titlesFromSummary(summary ?? '')).slice(0, MAX_TITLES)
  const name = firstText(item.action, item.tool, item.name, item.toolName, item.tool_name, fn?.name) ?? ''

  return {
    name,
    query: firstText(args?.query, args?.keyword, args?.q, item.query),
    freshness: firstText(args?.freshness, args?.timeRange, args?.time_range, item.freshness),
    url: firstText(args?.url, item.url, result?.url),
    hitCount:
      countOf(item.hitCount, item.hit_count, item.count, item.total, item.resultCount, item.result_count, result?.count, result?.total) ??
      (entries.length > 0 ? entries.length : null) ??
      matchCount(summary, HIT_PATTERNS),
    titles,
    charCount:
      countOf(item.chars, item.charCount, item.char_count, item.length, item.textLength, item.text_length, result?.chars, result?.length) ??
      textLengthOf(result) ??
      textLengthOf(item) ??
      matchCount(summary, CHAR_PATTERNS) ??
      (/fetch/i.test(name) ? bodyLength(summary) : null),
    summary,
    outputId: firstText(item.outputId, item.output_id, result?.id),
    raw: item,
  }
}

function listFromObject(value: unknown): unknown[] {
  if (typeof value === 'string') return [value]
  if (!isRaw(value)) return []
  for (const key of ['calls', 'toolCalls', 'tool_calls', 'tools', 'items']) {
    const nested = value[key]
    if (Array.isArray(nested)) return nested
  }
  return [value]
}

/**
 * 把 `tool_calls_json` 归一成页面可渲染的列表。
 *
 * <p>兼容四种形态：单对象、对象数组、`{calls:[...]}` 包装、以及整体是 JSON 字符串。
 */
export function parseToolCalls(value: unknown): ToolCallView[] {
  const source = typeof value === 'string' ? (parseJson(value) ?? value) : value
  if (source === null || source === undefined) return []
  const items = Array.isArray(source) ? source : listFromObject(source)
  const views: ToolCallView[] = []
  for (const item of items) {
    const view = toView(item)
    if (view) views.push(view)
  }
  return views
}

export function isWebSearchCall(call: ToolCallView): boolean {
  return /search/i.test(call.name)
}

export function isFetchCall(call: ToolCallView): boolean {
  return /fetch/i.test(call.name)
}
