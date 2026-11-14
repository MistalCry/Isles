import type { Gender, MediaItem } from '@/types/models'

const MINUTE = 60 * 1000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/** 统一展示层时间格式化：相对时间（近期）/ 本地日期时间（久远）。 */
export function formatRelativeTime(value?: string | null): string {
  if (!value) return '刚刚'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const diff = Date.now() - date.getTime()
  if (diff < MINUTE) return '刚刚'
  if (diff < HOUR) return `${Math.floor(diff / MINUTE)} 分钟前`
  if (diff < DAY) return `${Math.floor(diff / HOUR)} 小时前`
  if (diff < 7 * DAY) return `${Math.floor(diff / DAY)} 天前`
  return formatDate(date)
}

export function formatDate(date: Date): string {
  const year = date.getFullYear()
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function formatDateTime(value?: string | null): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const hours = `${date.getHours()}`.padStart(2, '0')
  const minutes = `${date.getMinutes()}`.padStart(2, '0')
  return `${formatDate(date)} ${hours}:${minutes}`
}

/** 大数字缩写：12345 -> 1.2万，满足列表紧凑展示。 */
export function formatCount(value?: number | null): string {
  if (value === null || value === undefined) return '0'
  if (value < 10000) return `${value}`
  const wan = value / 10000
  return `${wan >= 100 ? Math.round(wan) : wan.toFixed(1).replace(/\.0$/, '')}万`
}

/** 去 HTML 标签得到纯文本摘要（帖子卡片不使用 v-html，避免用户内容注入）。 */
export function stripHtml(html: string): string {
  if (!html) return ''
  const withoutTags = html.replace(/<[^>]*>/g, ' ')
  return withoutTags.replace(/\s+/g, ' ').trim()
}

export function excerpt(text: string, maxLength = 160): string {
  const plain = stripHtml(text)
  if (plain.length <= maxLength) return plain
  return `${plain.slice(0, maxLength)}…`
}

export function genderLabel(gender?: Gender | null): string {
  switch (gender) {
    case 'MALE':
      return '男'
    case 'FEMALE':
      return '女'
    case 'OTHER':
      return '其他'
    case 'UNKNOWN':
      return '保密'
    default:
      return ''
  }
}

/** 绝对地址用于复制分享链接。 */
export function absoluteUrl(path: string): string {
  if (/^https?:\/\//.test(path)) return path
  return `${window.location.origin}${path.startsWith('/') ? path : `/${path}`}`
}

export function sortMedia(media?: MediaItem[] | null): MediaItem[] {
  return (media ?? []).filter((item) => Boolean(item?.url))
}

export function isImage(item: MediaItem): boolean {
  return item.type === 'IMAGE'
}
