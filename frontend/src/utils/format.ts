import { currentLocale, t } from '@/i18n'
import type { Gender, MediaItem } from '@/types/models'
import { formatMentions } from './mention'

const MINUTE = 60 * 1000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/** 统一展示层时间格式化：相对时间（近期）/ 本地日期时间（久远）。 */
export function formatRelativeTime(value?: string | null): string {
  if (!value) return t('time.justNow')
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const diff = Date.now() - date.getTime()
  if (diff < MINUTE) return t('time.justNow')
  if (diff < HOUR) return t('time.minutesAgo', { n: Math.floor(diff / MINUTE) })
  if (diff < DAY) return t('time.hoursAgo', { n: Math.floor(diff / HOUR) })
  if (diff < 7 * DAY) return t('time.daysAgo', { n: Math.floor(diff / DAY) })
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

/**
 * 大数字缩写：中文 12345 -> 1.2万，英文 12345 -> 12K。
 * 用 Intl 紧凑记数法，避免为不同语言手写量级换算。
 */
export function formatCount(value?: number | null): string {
  if (value === null || value === undefined) return '0'
  if (value < 10000) return `${value}`
  return new Intl.NumberFormat(currentLocale(), {
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(value)
}

/** 去 HTML 标签得到纯文本摘要（帖子卡片不使用 v-html，避免用户内容注入）。 */
export function stripHtml(html: string): string {
  if (!html) return ''
  const withoutTags = html.replace(/<[^>]*>/g, ' ')
  return withoutTags.replace(/\s+/g, ' ').trim()
}

export function excerpt(text: string, maxLength = 160): string {
  // 卡片摘要里把 @[id:昵称] 还原成 @昵称，避免把 token 直接展示给用户
  const plain = formatMentions(stripHtml(text))
  if (plain.length <= maxLength) return plain
  return `${plain.slice(0, maxLength)}…`
}

export function genderLabel(gender?: Gender | null): string {
  switch (gender) {
    case 'MALE':
      return t('profile.male')
    case 'FEMALE':
      return t('profile.female')
    case 'OTHER':
      return t('common.other')
    case 'UNKNOWN':
      return t('gender.private')
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
